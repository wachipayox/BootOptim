#!/usr/bin/env python3
import argparse
import hashlib
import http.server
import json
import os
import re
import signal
import subprocess
import sys
import threading
import zipfile
from pathlib import Path

MARKER = "BOOTOPTIM_STARTUP phase=main_menu"
FORK_PROPERTY = "-Dboot_optim.modlauncherForkTrace=true"


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("MCEF_LOCAL_MIRROR " + (fmt % args), flush=True)


def start_mcef_mirror() -> tuple[http.server.ThreadingHTTPServer | None, threading.Thread | None]:
    root_text = os.environ.get("BOOTOPTIM_MCEF_MIRROR_ROOT", "").strip()
    if not root_text:
        return None, None
    root = Path(root_text).resolve()
    if not root.is_dir():
        raise RuntimeError(f"BOOTOPTIM_MCEF_MIRROR_ROOT does not exist: {root}")
    port = int(os.environ.get("BOOTOPTIM_MCEF_MIRROR_PORT", "18765"))

    def handler(*args, **kwargs):
        return QuietHandler(*args, directory=str(root), **kwargs)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
    thread = threading.Thread(target=server.serve_forever, name="mcef-local-mirror", daemon=True)
    thread.start()
    print(f"MCEF local mirror listening on http://127.0.0.1:{port} root={root}", flush=True)
    return server, thread


def terminate_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False, timeout=15)
        else:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
                return
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def capture_thread_dump(path: Path) -> None:
    lines: list[str] = []
    try:
        jps = subprocess.run(["jps", "-q"], capture_output=True, text=True, timeout=10, check=False)
        pids = [line.strip() for line in jps.stdout.splitlines() if line.strip().isdigit()]
    except Exception as exc:
        path.write_text(f"jps failed: {exc}\n", encoding="utf-8")
        return
    for pid in pids:
        lines.append(f"===== JVM {pid} =====\n")
        try:
            dump = subprocess.run(["jcmd", pid, "Thread.print"], capture_output=True, text=True, timeout=15, check=False)
            lines.append(dump.stdout)
            if dump.stderr:
                lines.append(dump.stderr)
        except Exception as exc:
            lines.append(f"jcmd failed: {exc}\n")
    path.write_text("".join(lines), encoding="utf-8", errors="replace")


def tail(path: Path, count: int = 250) -> str:
    if not path.exists():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-count:])


def wait_for_process(process: subprocess.Popen, timeout_seconds: int) -> tuple[bool, str]:
    try:
        process.wait(timeout=timeout_seconds)
        return True, f"process_exit_{process.returncode}"
    except subprocess.TimeoutExpired:
        return False, "timeout"


def fork_requested() -> bool:
    return FORK_PROPERTY in os.environ.get("BOOTOPTIM_PACK_EXTRA_JVM_ARGS", "").splitlines()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_fork_resolution(root: Path) -> dict:
    build_report_path = root / "modlauncher-fork-build.json"
    resolution_path = root / "modlauncher-fork-resolution.json"
    if not build_report_path.is_file() or not resolution_path.is_file():
        raise SystemExit("Fork identity gate: build/resolution report missing")
    build_report = json.loads(build_report_path.read_text(encoding="utf-8"))
    resolution = json.loads(resolution_path.read_text(encoding="utf-8"))

    records_by_path: dict[Path, list[dict]] = {}
    configuration_summary = {}
    for configuration in resolution.get("configurations", []):
        name = configuration.get("name")
        artifacts = configuration.get("artifacts", [])
        configuration_summary[name] = {
            "present": bool(configuration.get("present")),
            "can_be_resolved": bool(configuration.get("can_be_resolved")),
            "artifact_count": len(artifacts),
        }
        for artifact in artifacts:
            path = Path(artifact["file"]).resolve()
            if path.is_file():
                records_by_path.setdefault(path, []).append({
                    "configuration": name,
                    "component": artifact.get("component"),
                    "artifact": artifact.get("artifact"),
                })

    modlauncher_core: dict[Path, list[dict]] = {}
    securejar_core: dict[Path, list[dict]] = {}
    for path, records in records_by_path.items():
        try:
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if "cpw/mods/modlauncher/Launcher.class" in names:
                    modlauncher_core[path] = records
                if "cpw/mods/cl/ModuleClassLoader.class" in names:
                    securejar_core[path] = records
        except zipfile.BadZipFile:
            continue

    if len(modlauncher_core) != 1:
        raise SystemExit(f"Fork identity gate: expected exactly one resolved ModLauncher core JAR, found {list(modlauncher_core)}")
    if len(securejar_core) != 1:
        raise SystemExit(f"Fork identity gate: expected exactly one resolved SecureJarHandler core JAR, found {list(securejar_core)}")

    modlauncher, ml_records = next(iter(modlauncher_core.items()))
    sjh, sjh_records = next(iter(securejar_core.items()))
    resolved_sha = sha256(modlauncher)
    expected_sha = build_report.get("jar_sha256")
    if not expected_sha or resolved_sha != expected_sha:
        raise SystemExit(
            f"Fork identity gate: resolved ModLauncher SHA mismatch expected={expected_sha} actual={resolved_sha} path={modlauncher}"
        )
    if not any(record.get("component") == "cpw.mods:modlauncher:11.0.5" for record in ml_records):
        raise SystemExit(f"Fork identity gate: ModLauncher core has unexpected component provenance: {ml_records}")
    if not any(record.get("component") == "cpw.mods:securejarhandler:3.0.4" for record in sjh_records):
        raise SystemExit(f"Fork identity gate: SecureJarHandler core has unexpected component provenance: {sjh_records}")

    with zipfile.ZipFile(modlauncher) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace").replace("\r", "")
        if "BootOptim-Fork-Probe: agent94-post-accept-v2\n" not in manifest:
            raise SystemExit("Fork identity gate: manifest probe marker missing")
        if "BootOptim-Upstream-Commit: 901c6ea849ae21ee7d464cd97113e77a6101a734\n" not in manifest:
            raise SystemExit("Fork identity gate: upstream commit marker missing")

    describe = subprocess.run(
        ["jar", "--describe-module", "--file", str(modlauncher)], capture_output=True, text=True, check=True
    ).stdout
    first = next((line.strip() for line in describe.splitlines() if line.strip()), "")
    if not first.startswith("cpw.mods.modlauncher@11.0.5"):
        raise SystemExit(f"Fork identity gate: unexpected module identity: {first!r}")

    vmargs_candidates = sorted(
        path for path in (root / "build").rglob("*VmArgs.txt")
        if "packbenchmarkclient" in path.name.lower()
    )
    if len(vmargs_candidates) != 1:
        raise SystemExit(f"Fork identity gate: expected one generated pack benchmark VM args file, found {vmargs_candidates}")

    result = {
        "schema": 2,
        "vmargs": str(vmargs_candidates[0]),
        "module": first,
        "resolved_modlauncher": str(modlauncher),
        "resolved_modlauncher_sha256": resolved_sha,
        "modlauncher_provenance": ml_records,
        "resolved_securejarhandler": str(sjh),
        "securejarhandler_provenance": sjh_records,
        "modlauncher_core_count": len(modlauncher_core),
        "securejarhandler_core_count": len(securejar_core),
        "fork_manifest": "agent94-post-accept-v2",
        "upstream_commit": "901c6ea849ae21ee7d464cd97113e77a6101a734",
        "configurations": configuration_summary,
    }
    (root / "modlauncher-fork-identity.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("BOOTOPTIM_ML_FORK_PREFLIGHT " + json.dumps(result, sort_keys=True), flush=True)
    return result


def parse_kv(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", line))


def parse_fork_profile(console_text: str) -> dict:
    accept = None
    entry = None
    identity = []
    stages = []
    transformers = []
    for line in console_text.splitlines():
        if "BOOTOPTIM_ML_FORK_IDENTITY" in line:
            identity.append(parse_kv(line))
        elif "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY" in line:
            data = parse_kv(line)
            if data.get("event") == "transform_accept":
                accept = int(data["mono_ns"])
            elif data.get("event") == "bootstrap_entry":
                entry = int(data["mono_ns"])
        elif "BOOTOPTIM_ML_FORK " in line:
            data = parse_kv(line)
            labels_match = re.search(r"labels=(\[.*?\])(?:\s|$)", line)
            if labels_match:
                data["labels"] = labels_match.group(1)
            if data.get("stage") == "transformer":
                transformers.append(data)
            else:
                stages.append(data)

    if accept is None or entry is None:
        raise SystemExit("Fork profile missing strict transform_accept/bootstrap_entry boundary")
    if len(identity) != 1 or identity[0].get("module") != "cpw.mods.modlauncher":
        raise SystemExit(f"Fork runtime identity invalid: {identity}")

    strict = [t for t in transformers if "boot_optim_agent94_bootstrap_profile" in t.get("labels", "")]
    if len(strict) != 1:
        raise SystemExit(f"Expected exactly one strict Agent 94 transformer event, found {len(strict)}")
    strict_end = int(strict[0]["end_ns"])

    remaining = []
    for item in transformers:
        if item is strict[0]:
            continue
        if int(item.get("start_ns", "0")) >= strict_end:
            remaining.append({
                "owner": item.get("owner"),
                "labels": item.get("labels"),
                "start_ns": int(item["start_ns"]),
                "end_ns": int(item["end_ns"]),
                "elapsed_ns": int(item["elapsed_ns"]),
            })

    by_stage = {item.get("stage"): item for item in stages if item.get("stage")}
    required = ["plugins_after", "writer_create", "writer_accept", "writer_to_bytes", "class_transform_return"]
    missing = [name for name in required if name not in by_stage]
    if missing:
        raise SystemExit(f"Fork profile missing stages: {missing}")

    class_return = int(by_stage["class_transform_return"]["mono_ns"])
    plugins_after = by_stage["plugins_after"]
    result = {
        "schema": 1,
        "metric_type": "single-run monotonic phase wall / target-only callback wall",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "target": "net.minecraft.server.Bootstrap",
        "accept_ns": accept,
        "bootstrap_entry_ns": entry,
        "accept_to_entry_ns": entry - accept,
        "strict_transform_end_ns": strict_end,
        "accept_to_strict_transform_end_ns": strict_end - accept,
        "strict_end_to_plugins_after_start_ns": int(plugins_after["start_ns"]) - strict_end,
        "remaining_transformers": remaining,
        "remaining_transformer_callback_wall_sum_ns": sum(item["elapsed_ns"] for item in remaining),
        "plugins_after_ns": int(plugins_after["elapsed_ns"]),
        "writer_create_ns": int(by_stage["writer_create"]["elapsed_ns"]),
        "writer_accept_ns": int(by_stage["writer_accept"]["elapsed_ns"]),
        "writer_to_bytes_ns": int(by_stage["writer_to_bytes"]["elapsed_ns"]),
        "class_transform_return_ns": class_return,
        "class_transform_return_to_bootstrap_entry_ns": entry - class_return,
        "runtime_identity": identity[0],
    }
    if result["accept_to_entry_ns"] <= 0 or result["class_transform_return_to_bootstrap_entry_ns"] < 0:
        raise SystemExit(f"Fork profile monotonic ordering invalid: {result}")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", required=True)
    parser.add_argument("--iteration", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--rerun-tasks", action="store_true")
    args = parser.parse_args()

    root = Path.cwd()
    console_log = root / "exact-pack-console.log"
    thread_dump = root / "exact-pack-thread-dump.log"
    result_json = root / "result.json"
    latest_log = root / "run-pack-benchmark" / "logs" / "latest.log"
    startup_log = root / "run-pack-benchmark" / "logs" / "bootoptim-startup.log"
    selection_report = root / "resource-selection-check.json"
    selection_reference = root / "resource-selection-reference.txt"
    for path in (console_log, thread_dump, result_json, selection_report, selection_reference):
        path.unlink(missing_ok=True)

    fixture_root = os.environ.get("BOOTOPTIM_PACK_DIR", "").strip()
    if not fixture_root:
        raise SystemExit("BOOTOPTIM_PACK_DIR is required for resource contract validation.")
    selection_reference.write_bytes((Path(fixture_root) / "options.txt").read_bytes())

    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    fork_identity = None
    if fork_requested():
        repo = root / ".agent94-ml-fork-repo"
        subprocess.run([
            sys.executable, "tools/modlauncher-fork-probe/prepare_runtime.py",
            "--root", str(root), "--output", str(repo), "--report", str(root / "modlauncher-fork-build.json")
        ], cwd=root, check=True)
        os.environ["BOOTOPTIM_ML_FORK_REPO"] = str(repo)
        # Resolve the exact ModDev launch inputs before the timed Minecraft JVM exists.
        subprocess.run([gradle, "preparePackBenchmarkClientRun", "--no-daemon", "--console=plain"], cwd=root, check=True)
        resolution = root / "modlauncher-fork-resolution.json"
        subprocess.run([
            gradle,
            "-I", "tools/modlauncher-fork-probe/resolve_launch_files.gradle",
            "agent94WriteLaunchResolution",
            f"-Pagent94LaunchResolutionOutput={resolution}",
            "--no-daemon", "--console=plain"
        ], cwd=root, check=True)
        fork_identity = verify_fork_resolution(root)

    mirror_server = None
    process = None
    try:
        mirror_server, _ = start_mcef_mirror()
        command = [gradle, "runPackBenchmarkClient", "--no-daemon", "--console=plain"]
        if args.rerun_tasks:
            command.append("--rerun-tasks")
        print(f"Launching exact-pack benchmark variant={args.variant} iteration={args.iteration} timeout={args.timeout}s", flush=True)
        with console_log.open("w", encoding="utf-8", errors="replace") as output:
            kwargs = {"stdout": output, "stderr": subprocess.STDOUT, "cwd": root}
            if os.name == "nt":
                kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            else:
                kwargs["start_new_session"] = True
            process = subprocess.Popen(command, **kwargs)
            process_finished, reason = wait_for_process(process, args.timeout)

        if not process_finished:
            capture_thread_dump(thread_dump)
            if process is not None:
                terminate_tree(process)
            print(tail(console_log), file=sys.stderr)
            raise SystemExit(f"Exact-pack benchmark did not reach the main-menu marker within {args.timeout} seconds.")

        console_text = console_log.read_text(encoding="utf-8", errors="replace")
        if MARKER not in console_text:
            print(tail(console_log), file=sys.stderr)
            raise SystemExit(f"Exact-pack benchmark exited without the main-menu marker ({reason}).")
        if fork_requested() and "BOOTOPTIM_ML_FORK_IDENTITY" not in console_text:
            raise SystemExit("Fork preflight passed but runtime fork identity marker was absent")

        if not latest_log.is_file():
            raise SystemExit(f"Exact-pack run reached marker but latest.log is missing: {latest_log}")
        if not startup_log.is_file():
            raise SystemExit(f"Exact-pack run reached marker but startup report is missing: {startup_log}")

        latest_text = latest_log.read_text(encoding="utf-8", errors="replace")
        mixin_failures = ("InvalidInjectionException", "Mixin apply for mod boot_optim failed", "Mixin prepare for mod boot_optim failed")
        if any(pattern in latest_text for pattern in mixin_failures):
            raise SystemExit("BootOptim Mixin failure detected in exact-pack latest.log.")

        with selection_report.open("w", encoding="utf-8") as report:
            resource_check = subprocess.run([
                sys.executable, "tools/laptop-bench/check_resource_selection.py",
                "--reference", str(selection_reference), "--options", str(root / "run-pack-benchmark" / "options.txt"),
                "--log", str(latest_log)
            ], cwd=root, stdout=report, check=False)
        if resource_check.returncode != 0:
            raise SystemExit("Exact-pack resource contract failed; see resource-selection-check.json.")

        summary = subprocess.run([
            sys.executable, "scripts/exact-pack/summarize_startup.py", "single",
            "--latest", str(latest_log), "--startup", str(startup_log),
            "--variant", args.variant, "--iteration", str(args.iteration), "--output", str(result_json)
        ], cwd=root, check=False)
        if summary.returncode != 0:
            raise SystemExit(f"Exact-pack summarizer failed with exit {summary.returncode}")

        if fork_requested():
            result = json.loads(result_json.read_text(encoding="utf-8"))
            result["modlauncher_fork_identity"] = fork_identity
            result["modlauncher_fork_profile"] = parse_fork_profile(console_text)
            result_json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            print("BOOTOPTIM_ML_FORK_PROFILE " + json.dumps(result["modlauncher_fork_profile"], sort_keys=True), flush=True)
    finally:
        if process is not None:
            terminate_tree(process)
        if mirror_server is not None:
            mirror_server.shutdown()
            mirror_server.server_close()


if __name__ == "__main__":
    main()
