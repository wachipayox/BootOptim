#!/usr/bin/env python3
import argparse
import collections
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path
from urllib.parse import unquote

DEP_RE = re.compile(r"phase=dependency_discovery_end\b.*?elapsed_ms=([0-9]+(?:\.[0-9]+)?)")
MENU_RE = re.compile(r"phase=main_menu\b.*?uptime_ms=([0-9]+(?:\.[0-9]+)?)")


def union_rows(rows):
    intervals = sorted((int(r["start_ns"]), int(r["end_ns"])) for r in rows)
    if not intervals:
        return 0
    total = 0
    start, end = intervals[0]
    for a, b in intervals[1:]:
        if a <= end:
            end = max(end, b)
        else:
            total += end - start
            start, end = a, b
    return total + end - start


def metric(path, regex, name):
    matches = regex.findall(path.read_text(encoding="utf-8", errors="replace"))
    if len(matches) != 1:
        raise SystemExit(f"expected one {name} marker, got {len(matches)}")
    return float(matches[0])


def physical_path(runtime_path):
    try:
        direct = Path(runtime_path)
        if direct.is_file():
            return direct
    except (TypeError, ValueError):
        pass
    if not runtime_path.startswith("union:"):
        return None
    decoded = unquote(runtime_path)
    while decoded.startswith("union:"):
        decoded = decoded[len("union:"):]
    candidate = re.sub(r"#\d+$", "", decoded.split("!/", 1)[0])
    try:
        path = Path(candidate)
        return path if path.is_file() else None
    except (TypeError, ValueError):
        return None


def child_bytes(parent_payload, relative_path):
    name = relative_path.replace("\\", "/").lstrip("/")
    with zipfile.ZipFile(io.BytesIO(parent_payload), "r") as archive:
        return archive.read(name)


def resolve_strong_identity(loads, trace_path):
    payload_by_runtime_path = {}
    physical_cache = {}
    out = []
    for row in sorted(loads, key=lambda r: (int(r["start_ns"]), int(r.get("seq", 0)))):
        parent = row.get("parent_path")
        rel = row.get("relative_path")
        if not parent or not rel:
            raise SystemExit(f"missing parent/relative path: {row}")
        payload = payload_by_runtime_path.get(parent)
        source = "post_process_parent_chain" if payload is not None else None
        if payload is None:
            physical = physical_path(parent)
            if physical is not None:
                key = str(physical)
                payload = physical_cache.get(key)
                if payload is None:
                    payload = physical.read_bytes()
                    physical_cache[key] = payload
                source = "post_process_physical_file"
        if payload is None:
            raise SystemExit(f"cannot resolve strong parent identity for {parent} in {trace_path}")
        try:
            child = child_bytes(payload, rel)
        except Exception as exc:
            raise SystemExit(f"cannot read {rel} from {parent}: {exc}")
        enriched = dict(row)
        enriched["parent_sha256"] = hashlib.sha256(payload).hexdigest()
        enriched["parent_sha256_source"] = source
        enriched["child_sha256"] = hashlib.sha256(child).hexdigest()
        enriched["bytes"] = len(child)
        child_path = row.get("child_path")
        if child_path:
            payload_by_runtime_path[child_path] = child
        out.append(enriched)
    return out


def contained(row, outer):
    return (row.get("scan_id") == outer.get("scan_id")
            and int(row["start_ns"]) >= int(outer["start_ns"])
            and int(row["end_ns"]) <= int(outer["end_ns"]))


def phase(row, recursive_rows):
    return "recursive_detection" if any(contained(row, r) for r in recursive_rows) else "selected_materialization"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--startup", type=Path, required=True)
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    args = parser.parse_args()

    rows = [json.loads(line) for line in args.trace.read_text(encoding="utf-8").splitlines() if line.strip()]
    by = collections.defaultdict(list)
    for row in rows:
        by[row.get("kind")].append(row)
    headers = by["profile_header"]
    if not headers or by["profile_disabled"]:
        raise SystemExit("profile disabled or missing header")
    for header in headers:
        detail = header.get("detail") or ""
        required = ("expected_fml=4.0.43", "module_name=fml_loader", "module_fml=4.0.43", "implementation=jij_filesystem")
        if not all(token in detail for token in required):
            raise SystemExit(f"unexpected FML identity: {detail}")
    if any("throw=" in (r.get("detail") or "") for r in rows):
        raise SystemExit("instrumented stock callback threw")
    if by["selector_failure_callback"]:
        raise SystemExit("JarSelector failure callback ran")

    required_kinds = ["scan", "load_jij_filesystem", "filesystem_open", "descriptor_stream_open",
                      "descriptor_parse", "identify_callback", "selector_total", "selector_detect",
                      "selector_recursive_detect"]
    missing = [kind for kind in required_kinds if not by[kind]]
    if missing:
        raise SystemExit(f"missing required JiJ instrumentation: {missing}")
    scan_ids = {r.get("scan_id") for r in by["scan"]}
    if None in scan_ids or len(scan_ids) != len(by["scan"]):
        raise SystemExit(f"invalid scan IDs: {scan_ids}")
    for kind in required_kinds[1:]:
        if any(r.get("scan_id") not in scan_ids for r in by[kind]):
            raise SystemExit(f"{kind} contains events outside a scan")

    loads = resolve_strong_identity(by["load_jij_filesystem"], args.trace)
    fs_rows = by["filesystem_open"]
    for load in loads:
        direct_fs = [r for r in fs_rows if contained(r, load)]
        if len(direct_fs) != 1:
            raise SystemExit(f"expected exactly one direct jij: filesystem open per load; got {len(direct_fs)}")
        load["filesystem_open_ns"] = int(direct_fs[0]["duration_ns"])
        load["load_residual_ns"] = max(0, int(load["duration_ns"]) - load["filesystem_open_ns"])
        load["phase"] = phase(load, by["selector_recursive_detect"])
    for fs in fs_rows:
        if sum(contained(fs, load) for load in loads) != 1:
            raise SystemExit("filesystem-open event did not map uniquely to one load")

    present_streams = [r for r in by["descriptor_stream_open"] if r.get("result_present")]
    streams = collections.defaultdict(list)
    for row in present_streams:
        if row.get("object_id") is None:
            raise SystemExit("descriptor stream missing identity")
        streams[row["object_id"]].append(row)
    for row in by["descriptor_parse"]:
        matches = streams.get(row.get("object_id"), [])
        if len(matches) != 1:
            raise SystemExit(f"descriptor parse could not be tied uniquely to resource callback: {len(matches)}")

    groups = collections.defaultdict(list)
    for load in loads:
        groups[(load["parent_sha256"], load["relative_path"], load["child_sha256"])].append(load)
    repeated = {key: value for key, value in groups.items() if len(value) > 1}
    repeat_patterns = collections.Counter()
    repeated_records = []
    for key, occurrences in repeated.items():
        phases = tuple(r["phase"] for r in sorted(occurrences, key=lambda r: int(r["start_ns"])))
        repeat_patterns[" -> ".join(phases)] += 1
        repeated_records.append({
            "parent_sha256": key[0],
            "relative_path": key[1],
            "child_sha256": key[2],
            "bytes": int(occurrences[0]["bytes"]),
            "count": len(occurrences),
            "same_scan": len({r["scan_id"] for r in occurrences}) == 1,
            "pattern": " -> ".join(phases),
            "occurrences": [{
                "scan_id": r["scan_id"],
                "phase": r["phase"],
                "load_ms": int(r["duration_ns"]) / 1e6,
                "filesystem_open_ms": int(r["filesystem_open_ns"]) / 1e6,
                "load_residual_ms": int(r["load_residual_ns"]) / 1e6,
            } for r in sorted(occurrences, key=lambda r: int(r["start_ns"]))],
        })
    repeated_records.sort(key=lambda r: sum(o["load_ms"] for o in r["occurrences"]), reverse=True)

    scans = by["scan"]
    scan_ns = union_rows(scans)
    load_ns = union_rows(loads)
    fs_ns = union_rows(fs_rows)
    descriptor_open_ns = union_rows(by["descriptor_stream_open"])
    descriptor_parse_ns = union_rows(by["descriptor_parse"])
    identify_ns = union_rows(by["identify_callback"])
    top_level = loads + by["descriptor_stream_open"] + by["descriptor_parse"] + by["identify_callback"]
    scan_residual_ns = max(0, scan_ns - union_rows(top_level))
    load_residual_ns = max(0, load_ns - fs_ns)
    phase_counts = collections.Counter(r["phase"] for r in loads)
    phase_wall = {name: union_rows([r for r in loads if r["phase"] == name]) / 1e6 for name in phase_counts}

    scan_records = []
    for scan in sorted(scans, key=lambda r: int(r["start_ns"])):
        sid = scan["scan_id"]
        scoped = [r for r in loads if r["scan_id"] == sid]
        scan_records.append({
            "scan_id": sid,
            "wall_ms": int(scan["duration_ns"]) / 1e6,
            "loads": len(scoped),
            "recursive": sum(r["phase"] == "recursive_detection" for r in scoped),
            "selected": sum(r["phase"] == "selected_materialization" for r in scoped),
            "caller_stack": (scan.get("detail") or "").removeprefix("caller_stack="),
        })

    dependency_ms = metric(args.startup, DEP_RE, "dependency_discovery_end")
    menu_ms = metric(args.startup, MENU_RE, "main_menu")
    result = {
        "schema": 5,
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "fml_version": "4.0.43",
        "jarjar_version_from_fml_4x_sources": "0.4.1",
        "implementation": "jij_filesystem",
        "main_menu_ms": menu_ms,
        "dependency_discovery_ms": dependency_ms,
        "jarjar_scan_calls": len(scans),
        "jarjar_scan_union_ms": scan_ns / 1e6,
        "jarjar_share_of_dependency_pct": (scan_ns / 1e6) / dependency_ms * 100.0,
        "jij_load_calls": len(loads),
        "unique_strong_identities": len(groups),
        "repeated_strong_identities": len(repeated),
        "repeated_excess_calls": sum(len(v) - 1 for v in repeated.values()),
        "repeat_phase_patterns": dict(repeat_patterns),
        "load_phase_counts": dict(phase_counts),
        "load_phase_union_ms": phase_wall,
        "noninclusive_wall_ms": {
            "load_union": load_ns / 1e6,
            "filesystem_open_union_within_load": fs_ns / 1e6,
            "load_residual_after_filesystem_open": load_residual_ns / 1e6,
            "descriptor_stream_open_union": descriptor_open_ns / 1e6,
            "descriptor_parse_union": descriptor_parse_ns / 1e6,
            "identify_callback_union": identify_ns / 1e6,
            "scan_residual_after_measured_top_level_work": scan_residual_ns / 1e6,
        },
        "descriptor": {
            "resource_callback_calls": len(by["descriptor_stream_open"]),
            "present_streams": len(present_streams),
            "parse_calls": len(by["descriptor_parse"]),
        },
        "callbacks": {
            "source_producer_load_calls": len(loads),
            "resource_reader_calls": len(by["descriptor_stream_open"]),
            "identify_calls": len(by["identify_callback"]),
            "failure_calls": len(by["selector_failure_callback"]),
        },
        "scans": scan_records,
        "repeated_identities": repeated_records,
    }
    args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    nw = result["noninclusive_wall_ms"]
    lines = [
        "# Agent 113 — FML 4.0.43 JiJ duplicate attribution",
        "",
        f"Hosted exact-pack fresh JVM to `main_menu`: **{menu_ms:.0f} ms**. Dependency discovery inclusive wall: **{dependency_ms:.3f} ms**.",
        f"JarJar scan union: **{scan_ns/1e6:.3f} ms**; `loadModFileFrom(3)` union: **{load_ns/1e6:.3f} ms**.",
        "",
        f"Loads: **{len(loads)}**; strong identities: **{len(groups)}**; repeated identities: **{len(repeated)}**; excess repeated calls: **{sum(len(v)-1 for v in repeated.values())}**.",
        f"Repeat phase patterns: `{dict(repeat_patterns)}`.",
        "",
        "## Non-inclusive attribution",
        "",
        "| boundary | union wall ms |",
        "| --- | ---: |",
        f"| loadModFileFrom | {nw['load_union']:.3f} |",
        f"| ↳ jij: filesystem open | {nw['filesystem_open_union_within_load']:.3f} |",
        f"| ↳ load residual: findResource + URI/env + JarContents.of + pipeline.readModFile + probe | {nw['load_residual_after_filesystem_open']:.3f} |",
        f"| descriptor resource callback/open | {nw['descriptor_stream_open_union']:.3f} |",
        f"| descriptor MetadataIO parse/read | {nw['descriptor_parse_union']:.3f} |",
        f"| identify callback | {nw['identify_callback_union']:.3f} |",
        f"| JarJar scan residual outside measured top-level rows | {nw['scan_residual_after_measured_top_level_work']:.3f} |",
        "",
        f"Recursive detection loads: **{phase_counts.get('recursive_detection', 0)}** / **{phase_wall.get('recursive_detection', 0.0):.3f} ms** union. Selected materialization loads: **{phase_counts.get('selected_materialization', 0)}** / **{phase_wall.get('selected_materialization', 0.0):.3f} ms** union.",
        "",
        "Scan callsites:",
    ]
    for scan in scan_records:
        lines.append(f"- scan {scan['scan_id']}: {scan['wall_ms']:.3f} ms, loads={scan['loads']} (recursive={scan['recursive']}, selected={scan['selected']}), caller `{scan['caller_stack']}`")
    lines += ["", "Largest repeated strong identities:", ""]
    for row in repeated_records[:20]:
        occ = ", ".join(f"scan{o['scan_id']}:{o['phase']}={o['load_ms']:.3f}ms" for o in row["occurrences"])
        lines.append(f"- `{row['relative_path']}` {row['bytes']/(1024*1024):.2f} MiB, parent `{row['parent_sha256'][:12]}…`, child `{row['child_sha256'][:12]}…`, `{row['pattern']}`: {occ}")
    lines += [
        "",
        "Strong hashes/bytes are reconstructed after process exit. The timed JVM retains no FileSystem, JarContents, SecureJar, IModFile, reader, or stream object in profiler state.",
        "`DiscoveryPipeline.readModFile` is intentionally left inside load residual: its private implementation was not transformed in run 1, and it is not required to establish the JarSelector callback lifecycle requested here.",
        "Counts and inclusive selector timings are not savings. Any production route must preserve callback count/order/failure behavior and fresh object lifecycle.",
    ]
    args.markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
