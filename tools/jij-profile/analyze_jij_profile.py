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


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def union_intervals(intervals):
    intervals = sorted((int(a), int(b)) for a, b in intervals if int(b) >= int(a))
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


def union_rows(rows):
    return union_intervals((r["start_ns"], r["end_ns"]) for r in rows)


def startup_metric(path: Path, regex, label: str) -> float:
    matches = regex.findall(path.read_text(encoding="utf-8", errors="replace"))
    if len(matches) != 1:
        raise SystemExit(f"expected one {label} marker in {path}, got {len(matches)}")
    return float(matches[0])


def read_entry(parent_payload: bytes, relative_path: str) -> bytes:
    name = relative_path.replace("\\", "/").lstrip("/")
    with zipfile.ZipFile(io.BytesIO(parent_payload), "r") as archive:
        return archive.read(name)


def physical_path_from_runtime_path(runtime_path: str):
    try:
        direct = Path(runtime_path)
    except (TypeError, ValueError):
        direct = None
    if direct is not None and direct.is_file():
        return direct
    if not runtime_path.startswith("union:"):
        return None
    decoded = unquote(runtime_path)
    while decoded.startswith("union:"):
        decoded = decoded[len("union:"):]
    archive_part = decoded.split("!/", 1)[0]
    archive_part = re.sub(r"#\d+$", "", archive_part)
    try:
        candidate = Path(archive_part)
    except (TypeError, ValueError):
        return None
    return candidate if candidate.is_file() else None


def resolve_load_provenance(loads, trace_path: Path):
    payload_by_runtime_path = {}
    physical_payload_cache = {}
    resolved = []
    for row in sorted(loads, key=lambda r: (int(r["start_ns"]), int(r.get("seq", 0)))):
        parent_path = row.get("parent_path")
        relative_path = row.get("relative_path")
        if not parent_path or not relative_path:
            raise SystemExit(f"missing parent path/relative path in {trace_path}: {row}")
        parent_payload = None
        parent_source = None
        if parent_path in payload_by_runtime_path:
            parent_payload = payload_by_runtime_path[parent_path]
            parent_source = "post_process_parent_chain"
        else:
            physical = physical_path_from_runtime_path(parent_path)
            if physical is not None:
                cache_key = str(physical)
                parent_payload = physical_payload_cache.get(cache_key)
                if parent_payload is None:
                    parent_payload = physical.read_bytes()
                    physical_payload_cache[cache_key] = parent_payload
                parent_source = "post_process_physical_file"
        if parent_payload is None:
            raise SystemExit(f"cannot resolve strong parent identity for {parent_path} in {trace_path}")
        try:
            child_payload = read_entry(parent_payload, relative_path)
        except Exception as exc:
            raise SystemExit(f"cannot read embedded entry {relative_path} from {parent_path}: {exc}")
        parent_sha = sha256_bytes(parent_payload)
        child_sha = sha256_bytes(child_payload)
        child_path = row.get("child_path")
        if child_path:
            payload_by_runtime_path[child_path] = child_payload
        enriched = dict(row)
        enriched.update({
            "parent_sha256": parent_sha,
            "parent_sha256_source": parent_source,
            "child_sha256": child_sha,
            "bytes": len(child_payload),
        })
        resolved.append(enriched)
    return resolved


def contained(row, outer):
    return (row.get("scan_id") == outer.get("scan_id")
            and int(row["start_ns"]) >= int(outer["start_ns"])
            and int(row["end_ns"]) <= int(outer["end_ns"]))


def containing(row, outers):
    matches = [outer for outer in outers if contained(row, outer)]
    if not matches:
        return None
    return min(matches, key=lambda r: int(r["end_ns"]) - int(r["start_ns"]))


def phase_for_load(row, recursive_rows):
    return "recursive_detection" if containing(row, recursive_rows) else "selected_materialization"


def child_union_for_load(load, children):
    intervals = []
    matches = []
    for child in children:
        if contained(child, load):
            intervals.append((child["start_ns"], child["end_ns"]))
            matches.append(child)
    return union_intervals(intervals), matches


def load_trace(path: Path):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    headers = [r for r in rows if r.get("kind") == "profile_header"]
    disabled = [r for r in rows if r.get("kind") == "profile_disabled"]
    if not headers or disabled:
        raise SystemExit(f"invalid profile header/disabled state in {path}: headers={len(headers)} disabled={len(disabled)}")
    for header in headers:
        detail = header.get("detail") or ""
        required = ("expected_fml=4.0.43", "module_name=fml_loader", "module_fml=4.0.43", "implementation=jij_filesystem")
        if not all(token in detail for token in required):
            raise SystemExit(f"unexpected FML identity/implementation in {path}: {detail}")
    failures = [r for r in rows if "throw=" in (r.get("detail") or "")]
    if failures:
        raise SystemExit(f"stock JarJar callback failures observed in {path}: {failures[:3]}")

    by_kind = collections.defaultdict(list)
    for row in rows:
        by_kind[row.get("kind")].append(row)
    required_kinds = ["scan", "load_jij_filesystem", "filesystem_open", "reader_callback",
                      "descriptor_stream_open", "descriptor_parse", "identify_callback",
                      "selector_total", "selector_detect", "selector_recursive_detect"]
    missing = [kind for kind in required_kinds if not by_kind[kind]]
    if missing:
        raise SystemExit(f"missing required JiJ instrumentation in {path}: {missing}")
    if by_kind["selector_failure_callback"]:
        raise SystemExit(f"selector failure callback unexpectedly ran: {by_kind['selector_failure_callback'][:3]}")

    scan_ids = {r.get("scan_id") for r in by_kind["scan"]}
    if None in scan_ids or len(scan_ids) != len(by_kind["scan"]):
        raise SystemExit(f"invalid/duplicate scan ids: {scan_ids}")
    for kind in required_kinds[1:]:
        unknown = [r for r in by_kind[kind] if r.get("scan_id") not in scan_ids]
        if unknown:
            raise SystemExit(f"{kind} rows outside a known scan: {unknown[:3]}")

    loads = resolve_load_provenance(by_kind["load_jij_filesystem"], path)
    fs_rows = by_kind["filesystem_open"]
    reader_rows = by_kind["reader_callback"]
    for load in loads:
        fs = [r for r in fs_rows if contained(r, load)]
        readers = [r for r in reader_rows if contained(r, load)]
        if len(fs) != 1 or len(readers) != 1:
            raise SystemExit(f"expected exactly one direct JiJ filesystem open and reader callback per load; load={load} fs={len(fs)} readers={len(readers)}")

    present_streams = [r for r in by_kind["descriptor_stream_open"] if r.get("result_present")]
    stream_ids = collections.defaultdict(list)
    for row in present_streams:
        if row.get("object_id") is None:
            raise SystemExit(f"present descriptor stream missing object id: {row}")
        stream_ids[row["object_id"]].append(row)
    for row in by_kind["descriptor_parse"]:
        candidates = stream_ids.get(row.get("object_id"), [])
        if len(candidates) != 1:
            raise SystemExit(f"descriptor parse could not be mapped uniquely to one parent stream: parse={row} candidates={len(candidates)}")
        row["descriptor_parent_path"] = candidates[0].get("parent_path")
        row["descriptor_relative_path"] = candidates[0].get("relative_path")
    return by_kind, loads


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--trace", type=Path, required=True)
    p.add_argument("--startup", type=Path, required=True)
    p.add_argument("--json-output", type=Path, required=True)
    p.add_argument("--markdown-output", type=Path, required=True)
    args = p.parse_args()

    kinds, loads = load_trace(args.trace)
    dep_ms = startup_metric(args.startup, DEP_RE, "dependency_discovery_end")
    menu_ms = startup_metric(args.startup, MENU_RE, "main_menu")
    scans = kinds["scan"]
    recursive = kinds["selector_recursive_detect"]
    fs_rows = kinds["filesystem_open"]
    reader_rows = kinds["reader_callback"]
    descriptor_open = kinds["descriptor_stream_open"]
    descriptor_parse = kinds["descriptor_parse"]
    identify_rows = kinds["identify_callback"]

    for load in loads:
        load["phase"] = phase_for_load(load, recursive)
        fs_ns, fs = child_union_for_load(load, fs_rows)
        reader_ns, readers = child_union_for_load(load, reader_rows)
        nested_ns = union_rows(fs + readers)
        load["filesystem_open_ns"] = fs_ns
        load["reader_callback_ns"] = reader_ns
        load["load_residual_ns"] = max(0, int(load["duration_ns"]) - nested_ns)

    groups = collections.defaultdict(list)
    for load in loads:
        key = (load["parent_sha256"], load["relative_path"], load["child_sha256"])
        groups[key].append(load)
    repeated = {key: rows for key, rows in groups.items() if len(rows) > 1}

    repeated_records = []
    repeated_excess = 0
    repeated_within_scan = 0
    repeated_cross_scan_only = 0
    detection_then_selected = 0
    for key, rows in repeated.items():
        repeated_excess += len(rows) - 1
        per_scan = collections.defaultdict(list)
        for row in rows:
            per_scan[row["scan_id"]].append(row)
        within = any(len(v) > 1 for v in per_scan.values())
        if within:
            repeated_within_scan += 1
        else:
            repeated_cross_scan_only += 1
        has_pair = False
        for scan_rows in per_scan.values():
            phases = {r["phase"] for r in scan_rows}
            if "recursive_detection" in phases and "selected_materialization" in phases:
                has_pair = True
                break
        if has_pair:
            detection_then_selected += 1
        repeated_records.append({
            "parent_sha256": key[0],
            "relative_path": key[1],
            "child_sha256": key[2],
            "bytes": rows[0]["bytes"],
            "count": len(rows),
            "within_same_scan": within,
            "detection_then_selected_same_scan": has_pair,
            "occurrences": [{
                "scan_id": r["scan_id"],
                "phase": r["phase"],
                "ms": int(r["duration_ns"]) / 1e6,
                "filesystem_open_ms": int(r["filesystem_open_ns"]) / 1e6,
                "reader_callback_ms": int(r["reader_callback_ns"]) / 1e6,
                "load_residual_ms": int(r["load_residual_ns"]) / 1e6,
            } for r in sorted(rows, key=lambda r: int(r["start_ns"]))],
        })
    repeated_records.sort(key=lambda r: sum(o["ms"] for o in r["occurrences"]), reverse=True)

    scan_ns = union_rows(scans)
    load_ns = union_rows(loads)
    fs_ns = union_rows(fs_rows)
    reader_ns = union_rows(reader_rows)
    descriptor_open_ns = union_rows(descriptor_open)
    descriptor_parse_ns = union_rows(descriptor_parse)
    identify_ns = union_rows(identify_rows)
    top_level_rows = loads + descriptor_open + descriptor_parse + identify_rows
    covered_ns = union_rows(top_level_rows)
    scan_residual_ns = max(0, scan_ns - covered_ns)
    load_child_union_ns = union_rows(fs_rows + reader_rows)
    load_residual_ns = max(0, load_ns - load_child_union_ns)

    phase_counts = collections.Counter(r["phase"] for r in loads)
    phase_wall = {phase: union_rows([r for r in loads if r["phase"] == phase]) / 1e6 for phase in phase_counts}
    scan_rows = []
    for scan in sorted(scans, key=lambda r: int(r["start_ns"])):
        sid = scan["scan_id"]
        scoped_loads = [r for r in loads if r["scan_id"] == sid]
        scan_rows.append({
            "scan_id": sid,
            "ms": int(scan["duration_ns"]) / 1e6,
            "load_calls": len(scoped_loads),
            "recursive_detection_loads": sum(r["phase"] == "recursive_detection" for r in scoped_loads),
            "selected_materialization_loads": sum(r["phase"] == "selected_materialization" for r in scoped_loads),
            "caller_stack": (scan.get("detail") or "").removeprefix("caller_stack="),
        })

    result = {
        "schema": 4,
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "fml_version": "4.0.43",
        "implementation": "jij_filesystem",
        "main_menu_ms": menu_ms,
        "dependency_discovery_ms": dep_ms,
        "jarjar_scan_calls": len(scans),
        "jarjar_scan_union_ms": scan_ns / 1e6,
        "jarjar_share_of_dependency_pct": (scan_ns / 1e6) / dep_ms * 100.0,
        "jij_load_calls": len(loads),
        "unique_strong_provenance": len(groups),
        "repeated_strong_provenance": len(repeated),
        "repeated_excess_load_calls": repeated_excess,
        "repeated_keys_within_same_scan": repeated_within_scan,
        "repeated_keys_cross_scan_only": repeated_cross_scan_only,
        "repeated_keys_detection_then_selected_same_scan": detection_then_selected,
        "load_phase_counts": dict(phase_counts),
        "load_phase_union_ms": phase_wall,
        "noninclusive_wall_ms": {
            "load_union": load_ns / 1e6,
            "filesystem_open_union_within_loads": fs_ns / 1e6,
            "reader_callback_union_within_loads": reader_ns / 1e6,
            "load_residual_after_fs_and_reader": load_residual_ns / 1e6,
            "descriptor_stream_open_union": descriptor_open_ns / 1e6,
            "descriptor_parse_union": descriptor_parse_ns / 1e6,
            "identify_callback_union": identify_ns / 1e6,
            "scan_residual_after_top_level_measured_work": scan_residual_ns / 1e6,
        },
        "descriptor": {
            "stream_open_calls": len(descriptor_open),
            "present_streams": sum(bool(r.get("result_present")) for r in descriptor_open),
            "parse_calls": len(descriptor_parse),
        },
        "callbacks": {
            "reader_calls": len(reader_rows),
            "identify_calls": len(identify_rows),
            "failure_calls": len(kinds["selector_failure_callback"]),
        },
        "scans": scan_rows,
        "repeated_identities": repeated_records,
        "top_loads": sorted(({
            "ms": int(r["duration_ns"]) / 1e6,
            "phase": r["phase"],
            "scan_id": r["scan_id"],
            "bytes": int(r["bytes"]),
            "parent_sha256": r["parent_sha256"],
            "relative_path": r["relative_path"],
            "child_sha256": r["child_sha256"],
            "filesystem_open_ms": int(r["filesystem_open_ns"]) / 1e6,
            "reader_callback_ms": int(r["reader_callback_ns"]) / 1e6,
            "load_residual_ms": int(r["load_residual_ns"]) / 1e6,
        } for r in loads), key=lambda x: x["ms"], reverse=True)[:20],
    }
    args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    nw = result["noninclusive_wall_ms"]
    lines = [
        "# Agent 113 — FML 4.0.43 JiJ duplicate attribution",
        "",
        f"Hosted exact-pack fresh JVM, endpoint `main_menu`: **{menu_ms:.0f} ms**. Dependency discovery inclusive wall: **{dep_ms:.3f} ms**.",
        "",
        "| JarJar scan union | load calls | unique strong identities | repeated identities | excess repeated calls | same-scan repeated keys |",
        "| ---: | ---: | ---: | ---: | ---: | ---: |",
        f"| {scan_ns/1e6:.3f} ms | {len(loads)} | {len(groups)} | {len(repeated)} | {repeated_excess} | {repeated_within_scan} |",
        "",
        "## Non-inclusive wall attribution",
        "",
        "`load` contains filesystem-open and reader callback. Descriptor/identify rows are siblings in JarSelector; selector phase rows are inclusive and are not added.",
        "",
        "| phase | union wall ms |",
        "| --- | ---: |",
        f"| JiJ load union | {nw['load_union']:.3f} |",
        f"| ↳ filesystem open | {nw['filesystem_open_union_within_loads']:.3f} |",
        f"| ↳ reader callback | {nw['reader_callback_union_within_loads']:.3f} |",
        f"| ↳ load residual (findResource/URI/JarContents/profiler overhead) | {nw['load_residual_after_fs_and_reader']:.3f} |",
        f"| descriptor stream open | {nw['descriptor_stream_open_union']:.3f} |",
        f"| descriptor parse/read | {nw['descriptor_parse_union']:.3f} |",
        f"| identify callback | {nw['identify_callback_union']:.3f} |",
        f"| scan residual after measured top-level work | {nw['scan_residual_after_top_level_measured_work']:.3f} |",
        "",
        "## Why loads repeat",
        "",
        f"Loads inside `JarSelector.recursivelyDetectContainedJars`: **{phase_counts.get('recursive_detection', 0)}** calls / **{phase_wall.get('recursive_detection', 0.0):.3f} ms** union.",
        f"Loads after recursive detection for selected materialization: **{phase_counts.get('selected_materialization', 0)}** calls / **{phase_wall.get('selected_materialization', 0.0):.3f} ms** union.",
        f"Strong keys seen in both phases of the same scan: **{detection_then_selected}**.",
        "",
        "Scan callsites:",
    ]
    for row in scan_rows:
        lines.append(f"- scan {row['scan_id']}: {row['ms']:.3f} ms, loads={row['load_calls']} (recursive={row['recursive_detection_loads']}, selected={row['selected_materialization_loads']}), caller `{row['caller_stack']}`")
    lines += ["", "Largest repeated strong identities:", ""]
    for row in repeated_records[:15]:
        phases = ", ".join(f"scan{o['scan_id']}:{o['phase']}={o['ms']:.3f}ms" for o in row["occurrences"])
        lines.append(f"- `{row['relative_path']}` {row['bytes']/(1024*1024):.2f} MiB, parent `{row['parent_sha256'][:12]}…`, child `{row['child_sha256'][:12]}…`, count={row['count']}, same_scan={row['within_same_scan']}: {phases}")
    lines += [
        "",
        "All SHA-256 identities and byte sizes are reconstructed only after Minecraft exits. The timed JVM stores only strings/primitives/object identity hashes and never retains or reuses `FileSystem`, `JarContents`, `SecureJar`, `IModFile`, reader or stream objects.",
        "Repeated calls are observations, not savings: stock callback count/order and fresh-object lifecycle remain semantic constraints.",
    ]
    args.markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
