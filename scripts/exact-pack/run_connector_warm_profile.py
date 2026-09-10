#!/usr/bin/env python3
"""Prime Connector's own cache once, then profile the identical exact pack without A/B comparison."""

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import run_startup


def clear_runtime_outputs(root: Path) -> None:
    for relative in (
        Path("result.json"),
        Path("exact-pack-console.log"),
        Path("exact-pack-thread-dump.log"),
        Path("resource-selection-check.json"),
        Path("resource-selection-reference.txt"),
        Path("run-pack-benchmark/logs"),
        Path("run-pack-benchmark/crash-reports"),
        Path("run-pack-benchmark/mods/mcef-cache"),
    ):
        target = root / relative
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        else:
            target.unlink(missing_ok=True)


def launch(root: Path, console: Path, timeout: int) -> None:
    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    command = [gradle, "runPackBenchmarkClient", "-x", "preparePackBenchmark", "--no-daemon", "--console=plain"]
    with console.open("w", encoding="utf-8", errors="replace") as output:
        kwargs = {"stdout": output, "stderr": subprocess.STDOUT, "cwd": root}
        if os.name == "nt":
            kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        process = subprocess.Popen(command, **kwargs)
        finished, reason = run_startup.wait_for_process(process, timeout)
    if not finished:
        run_startup.capture_thread_dump(root / "exact-pack-thread-dump.log")
        run_startup.terminate_tree(process)
        raise SystemExit(f"Connector warm profile timed out ({reason}).")
    if process.returncode != 0:
        raise SystemExit(f"Connector warm profile JVM exited {process.returncode}; see {console}.")
    text = console.read_text(encoding="utf-8", errors="replace")
    if run_startup.MARKER not in text:
        raise SystemExit("Connector warm profile JVM exited without main-menu marker.")


def copy_if_exists(source: Path, destination: Path) -> None:
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)


def union_ns(scopes: list[dict]) -> int:
    intervals = sorted((int(s["start_mono_ns"]), int(s["end_mono_ns"])) for s in scopes)
    total = 0
    end = None
    for start, stop in intervals:
        if end is None or start > end:
            total += max(0, stop - start)
            end = stop
        elif stop > end:
            total += stop - end
            end = stop
    return total


def summarize_resources(scopes: list[dict]) -> dict:
    grouped: dict[str, dict] = {}
    for scope in scopes:
        raw = scope.get("resource")
        resource = "<null>" if raw is None else str(raw)
        duration_ns = max(0, int(scope["duration_ns"]))
        row = grouped.setdefault(resource, {"calls": 0, "total_ns": 0, "max_ns": 0})
        row["calls"] += 1
        row["total_ns"] += duration_ns
        row["max_ns"] = max(row["max_ns"], duration_ns)
    rows = [
        {
            "resource": resource,
            "calls": data["calls"],
            "total_ms": round(data["total_ns"] / 1_000_000.0, 3),
            "max_ms": round(data["max_ns"] / 1_000_000.0, 3),
        }
        for resource, data in grouped.items()
    ]
    rows.sort(key=lambda row: (-row["total_ms"], row["resource"]))
    return {
        "calls": len(scopes),
        "unique_resources": len(grouped),
        "repeated_resource_calls": sum(max(0, row["calls"] - 1) for row in rows),
        "resources": rows,
    }


def analyze_connector(trace_path: Path, boot_trace_path: Path) -> dict:
    records = [json.loads(line) for line in trace_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    header = next(r for r in records if r.get("record") == "connector_trace_header")
    summary = next(r for r in records if r.get("record") == "connector_trace_summary")
    scopes = [r for r in records if r.get("record") == "scope"]
    caches = [r for r in records if r.get("record") == "cache"]
    if summary.get("unfinished_scopes") != 0 or summary.get("trace_errors") != 0:
        raise SystemExit(f"Connector trace integrity failure: {summary}")
    ids = {int(s["id"]): s for s in scopes}
    for scope in scopes:
        parent = int(scope.get("parent_id", 0))
        predecessor = int(scope.get("predecessor_id", 0))
        if parent and parent not in ids:
            raise SystemExit(f"Connector trace missing parent {parent}")
        if predecessor and predecessor not in ids:
            raise SystemExit(f"Connector trace missing predecessor {predecessor}")
        if parent:
            p = ids[parent]
            if int(scope["start_mono_ns"]) < int(p["start_mono_ns"]) or int(scope["end_mono_ns"]) > int(p["end_mono_ns"]):
                raise SystemExit(f"Connector child scope escapes parent: {scope['id']} -> {parent}")

    by_phase: dict[str, list[dict]] = {}
    for scope in scopes:
        by_phase.setdefault(scope["phase"], []).append(scope)
    required = (
        "connector_dependency_locator_callback",
        "connector_locate_fabric_mods",
        "connector_fabric_candidate_scan",
        "connector_cache_transformable_jar",
        "connector_fabric_metadata",
        "connector_transform_cache_validation",
        "connector_transform_cache_read_all_bytes",
        "connector_transform_cache_sha256",
        "connector_module_descriptor",
        "connector_dependency_resolution",
        "connector_transform_dispatch",
        "connector_split_package_merge",
        "connector_fresh_modfile_create",
        "connector_forge_package_filter",
        "connector_embedded_jarjar_callback",
    )
    missing = [phase for phase in required if not by_phase.get(phase)]
    if missing:
        raise SystemExit(f"Connector warm trace missing required phases: {missing}")

    phase_union_ms = {phase: round(union_ns(values) / 1_000_000.0, 3) for phase, values in sorted(by_phase.items())}
    transform_cache = [c for c in caches if c.get("kind") == "transform"]
    nested_cache = [c for c in caches if c.get("kind") == "nested_extract"]
    if not transform_cache:
        raise SystemExit("Connector warm trace recorded no transform-cache decisions.")

    validation_exclusive_ns = 0
    for validation in by_phase["connector_transform_cache_validation"]:
        children = [s for s in scopes if int(s.get("parent_id", 0)) == int(validation["id"])
                    and s["phase"] in {"connector_transform_cache_read_all_bytes", "connector_transform_cache_sha256"}]
        validation_exclusive_ns += max(0, int(validation["duration_ns"]) - union_ns(children))

    callback_residual_ns = 0
    for callback in by_phase["connector_dependency_locator_callback"]:
        direct = [s for s in scopes if int(s.get("parent_id", 0)) == int(callback["id"])]
        callback_residual_ns += max(0, int(callback["duration_ns"]) - union_ns(direct))

    locate_residual_ns = 0
    for locate in by_phase["connector_locate_fabric_mods"]:
        direct = [s for s in scopes if int(s.get("parent_id", 0)) == int(locate["id"])]
        locate_residual_ns += max(0, int(locate["duration_ns"]) - union_ns(direct))

    resource_phases = (
        "connector_split_fabric_jar_packages",
        "connector_split_existing_mod_packages",
        "connector_split_loaded_module_packages",
        "connector_split_analyze_package",
        "connector_locate_previous_mod_projection",
        "connector_locate_should_ignore_mod",
        "connector_locate_duplicate_handling",
        "connector_locate_nested_discovery",
        "connector_locate_nested_prepare",
    )
    resource_breakdown = {
        phase: summarize_resources(by_phase.get(phase, []))
        for phase in resource_phases
    }

    causal = dependency_overlap(header, by_phase["connector_dependency_locator_callback"], boot_trace_path)
    return {
        "schema_version": 2,
        "connector_version": header["connector_version"],
        "connector_commit": header["connector_commit"],
        "scope_count": len(scopes),
        "cache_event_count": len(caches),
        "phase_union_ms": phase_union_ms,
        "resource_breakdown": resource_breakdown,
        "transform_cache": {
            "decisions": len(transform_cache),
            "hits": sum(bool(c["hit"]) for c in transform_cache),
            "misses": sum(not bool(c["hit"]) for c in transform_cache),
            "hit_ratio": round(sum(bool(c["hit"]) for c in transform_cache) / len(transform_cache), 6),
            "validation_exclusive_sidecar_output_residual_ms": round(validation_exclusive_ns / 1_000_000.0, 3),
        },
        "nested_extract_cache": {
            "decisions": len(nested_cache),
            "hits": sum(bool(c["hit"]) for c in nested_cache),
            "misses": sum(not bool(c["hit"]) for c in nested_cache),
        },
        "callback_publication_exclusive_residual_ms": round(callback_residual_ns / 1_000_000.0, 3),
        "locate_fabric_mods_exclusive_residual_ms": round(locate_residual_ns / 1_000_000.0, 3),
        "dependency_discovery_causal_overlap": causal,
        "interpretation": "observational warm-cache wall only; prime run is setup, not an A/B control",
    }


def dependency_overlap(connector_header: dict, callbacks: list[dict], boot_trace_path: Path) -> dict:
    records = [json.loads(line) for line in boot_trace_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    header = next(r for r in records if r.get("record") == "trace_header")
    events = [r for r in records if r.get("record") == "event"]
    begins = {int(e["task_id"]): e for e in events if e.get("type") == "task_begin" and e.get("phase") == "dependency_discovery"}
    intervals = []
    for event in events:
        if event.get("type") != "task_end" or event.get("phase") != "dependency_discovery":
            continue
        task_id = int(event["task_id"])
        begin = begins.get(task_id)
        if begin:
            origin = int(header["trace_origin_epoch_ms"]) * 1_000_000
            intervals.append((origin + int(begin["mono_ns"]), origin + int(event["mono_ns"])))
    if not intervals:
        raise SystemExit("Boot trace has no complete dependency_discovery interval.")
    connector_origin_epoch = int(connector_header["trace_origin_epoch_ms"]) * 1_000_000
    connector_origin_mono = int(connector_header["trace_origin_mono_ns"])
    callback_intervals = [
        (connector_origin_epoch + int(s["start_mono_ns"]) - connector_origin_mono,
         connector_origin_epoch + int(s["end_mono_ns"]) - connector_origin_mono)
        for s in callbacks
    ]
    overlap = 0
    for start, stop in callback_intervals:
        for dep_start, dep_stop in intervals:
            overlap += max(0, min(stop, dep_stop) - max(start, dep_start))
    callback_wall = sum(max(0, b - a) for a, b in callback_intervals)
    return {
        "callback_wall_ms": round(callback_wall / 1_000_000.0, 3),
        "overlap_ms": round(overlap / 1_000_000.0, 3),
        "overlap_ratio": round(overlap / callback_wall, 6) if callback_wall else 0.0,
        "dependency_interval_count": len(intervals),
    }


def validate_measured(root: Path, variant: str, iteration: int) -> None:
    latest = root / "run-pack-benchmark/logs/latest.log"
    startup = root / "run-pack-benchmark/logs/bootoptim-startup.log"
    if not latest.is_file() or not startup.is_file():
        raise SystemExit("Measured warm run reached menu but required startup logs are missing.")
    latest_text = latest.read_text(encoding="utf-8", errors="replace")
    if any(p in latest_text for p in ("InvalidInjectionException", "Mixin apply for mod boot_optim failed", "Mixin prepare for mod boot_optim failed")):
        raise SystemExit("BootOptim Mixin failure detected in Connector warm profile.")

    fixture = Path(os.environ["BOOTOPTIM_PACK_DIR"])
    reference = root / "resource-selection-reference.txt"
    reference.write_bytes((fixture / "options.txt").read_bytes())
    with (root / "resource-selection-check.json").open("w", encoding="utf-8") as report:
        check = subprocess.run([
            sys.executable, "tools/laptop-bench/check_resource_selection.py",
            "--reference", str(reference), "--options", str(root / "run-pack-benchmark/options.txt"),
            "--log", str(latest)], cwd=root, stdout=report, check=False)
    if check.returncode != 0:
        raise SystemExit("Connector warm exact-pack resource contract failed.")
    summary = subprocess.run([
        sys.executable, "scripts/exact-pack/summarize_startup.py", "single",
        "--latest", str(latest), "--startup", str(startup), "--variant", variant,
        "--iteration", str(iteration), "--output", str(root / "result.json")], cwd=root, check=False)
    if summary.returncode != 0:
        raise SystemExit(f"Connector warm summarizer failed with exit {summary.returncode}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", required=True)
    parser.add_argument("--iteration", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--rerun-tasks", action="store_true")
    args = parser.parse_args()
    root = Path.cwd()
    warm_root = root / "connector-warm-results"
    shutil.rmtree(warm_root, ignore_errors=True)
    warm_root.mkdir(parents=True)

    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    subprocess.run([gradle, "preparePackBenchmark", "--no-daemon", "--console=plain"], cwd=root, check=True)
    subprocess.run([gradle, ":bootstrap:patchConnectorWarmResidual", "--no-daemon", "--console=plain"], cwd=root, check=True)

    mirror_server = None
    try:
        mirror_server, _ = run_startup.start_mcef_mirror()
        clear_runtime_outputs(root)
        prime_console = warm_root / "prime-console.log"
        launch(root, prime_console, args.timeout)
        cache_root = root / "run-pack-benchmark/.cache/connector"
        sidecars = list(cache_root.rglob("*.input")) if cache_root.is_dir() else []
        if not sidecars:
            raise SystemExit("Prime run produced no Connector cache sidecars; warm profile is invalid.")
        copy_if_exists(root / "run-pack-benchmark/logs/bootoptim-connector-warm.jsonl", warm_root / "prime-connector-trace.jsonl")
        copy_if_exists(root / "run-pack-benchmark/logs/bootoptim-trace.jsonl", warm_root / "prime-boot-trace.jsonl")
        (warm_root / "prime-cache.json").write_text(json.dumps({"sidecars": len(sidecars)}, indent=2) + "\n", encoding="utf-8")

        clear_runtime_outputs(root)
        measured_console = root / "exact-pack-console.log"
        launch(root, measured_console, args.timeout)
        validate_measured(root, args.variant, args.iteration)
        connector_trace = root / "run-pack-benchmark/logs/bootoptim-connector-warm.jsonl"
        boot_trace = root / "run-pack-benchmark/logs/bootoptim-trace.jsonl"
        if not connector_trace.is_file() or not boot_trace.is_file():
            raise SystemExit("Measured warm run is missing Connector or BootOptim trace output.")
        analysis = analyze_connector(connector_trace, boot_trace)
        analysis["prime_cache_sidecars"] = len(sidecars)
        (warm_root / "analysis.json").write_text(json.dumps(analysis, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        result_path = root / "result.json"
        result = json.loads(result_path.read_text(encoding="utf-8"))
        result["connector_warm_profile"] = analysis
        result_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    finally:
        if mirror_server is not None:
            mirror_server.shutdown()
            mirror_server.server_close()


if __name__ == "__main__":
    main()
