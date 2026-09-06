"""Parse BOOTOPTIM_VARIANCE lines after Java exits; never poll latest.log during startup."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

MARKER = "BOOTOPTIM_VARIANCE "
INT_FIELDS = {
    "seq", "scope", "mono_ns", "wall_epoch_ms", "jvm_start_epoch_ms", "uptime_ms",
    "thread_id", "gc_count", "gc_time_ms", "gc_count_delta", "gc_time_delta_ms",
}
FLOAT_FIELDS = {
    "process_cpu_ms", "thread_cpu_ms", "heap_used_mib", "heap_committed_mib",
    "heap_max_mib", "available_memory_mib", "elapsed_ms", "process_cpu_delta_ms",
    "owner_thread_cpu_delta_ms", "heap_used_delta_mib", "available_memory_delta_mib",
}
REQUIRED = {
    ("transformation_service_construct", "point"),
    ("root_mod_discovery", "end"),
    ("dependency_discovery", "end"),
    ("mod_entrypoint", "point"),
    ("resource_reload", "end"),
    ("reload_all_preparations", "point"),
    ("block_models", "end"),
    ("block_states", "end"),
    ("atlas_schedule_load", "end"),
    ("model_bakery_init", "end"),
    ("bake_models", "end"),
    ("load_models", "end"),
    ("model_manager_reload", "end"),
    ("fancymenu_preload", "end"),
    ("main_menu_opening", "point"),
}


def parse_line(line: str):
    index = line.find(MARKER)
    if index < 0:
        return None
    payload = line[index + len(MARKER):].strip()
    record = {}
    for token in payload.split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        if key in INT_FIELDS:
            try:
                record[key] = int(value)
            except ValueError:
                record[key] = None
        elif key in FLOAT_FIELDS:
            try:
                record[key] = float(value)
            except ValueError:
                record[key] = None
        else:
            record[key] = value
    return record if record.get("phase") and record.get("event") else None


def parse_lines(lines: Iterable[str]):
    return [record for line in lines if (record := parse_line(line)) is not None]


def _scope_summaries(records):
    starts = {}
    summaries = []
    warnings = []
    for record in records:
        scope = record.get("scope", 0)
        if not scope:
            continue
        key = (record.get("phase"), scope)
        if record.get("event") == "start":
            if key in starts:
                warnings.append(f"duplicate_start:{key[0]}:{scope}")
            starts[key] = record
        elif record.get("event") == "end":
            start = starts.pop(key, None)
            if start is None:
                warnings.append(f"unmatched_end:{key[0]}:{scope}")
            elapsed = record.get("elapsed_ms", -1.0)
            process_cpu = record.get("process_cpu_delta_ms", -1.0)
            summaries.append({
                "phase": record.get("phase"),
                "scope": scope,
                "subject": record.get("subject"),
                "start_uptime_ms": None if start is None else start.get("uptime_ms"),
                "end_uptime_ms": record.get("uptime_ms"),
                "wall_ms": elapsed,
                "process_cpu_ms": process_cpu,
                "owner_thread_cpu_ms": record.get("owner_thread_cpu_delta_ms"),
                "avg_process_cores": None if elapsed is None or elapsed <= 0 or process_cpu is None or process_cpu < 0
                    else round(process_cpu / elapsed, 4),
                "gc_count_delta": record.get("gc_count_delta"),
                "gc_time_delta_ms": record.get("gc_time_delta_ms"),
                "heap_used_delta_mib": record.get("heap_used_delta_mib"),
                "available_memory_delta_mib": record.get("available_memory_delta_mib"),
            })
    for phase, scope in sorted(starts):
        warnings.append(f"unmatched_start:{phase}:{scope}")
    return summaries, warnings


def summarize(records, max_early_uptime_ms=60_000):
    records = sorted(records, key=lambda row: (row.get("mono_ns") is None, row.get("mono_ns") or 0))
    invalid = []
    warnings = []
    if not records:
        return {"valid": False, "invalid_reasons": ["no_variance_records"], "warnings": [], "scopes": [], "markers": []}

    first = records[0]
    first_uptime = first.get("uptime_ms")
    if first.get("phase") != "transformation_service_construct":
        invalid.append("first_probe_is_not_transformation_service_construct")
    if first_uptime is None or first_uptime > max_early_uptime_ms:
        invalid.append(f"early_probe_uptime_exceeds_{max_early_uptime_ms}ms")

    wall = first.get("wall_epoch_ms")
    jvm_start = first.get("jvm_start_epoch_ms")
    if wall is not None and jvm_start is not None and first_uptime is not None:
        if abs((wall - jvm_start) - first_uptime) > 5_000:
            invalid.append("jvm_start_wall_uptime_inconsistent")

    present = {(row.get("phase"), row.get("event")) for row in records}
    missing = sorted(REQUIRED - present)
    invalid.extend(f"missing:{phase}:{event}" for phase, event in missing)

    menu_mono = next((row.get("mono_ns") for row in records
                      if row.get("phase") == "main_menu_opening" and row.get("event") == "point"), None)
    reload_starts = [row for row in records
                     if row.get("phase") == "resource_reload" and row.get("event") == "start"
                     and (menu_mono is None or row.get("mono_ns", 0) <= menu_mono)]
    if len(reload_starts) != 1:
        invalid.append(f"resource_reload_count_before_menu:{len(reload_starts)}")

    scopes, scope_warnings = _scope_summaries(records)
    warnings.extend(scope_warnings)

    previous_mono = None
    for row in records:
        mono = row.get("mono_ns")
        if mono is None:
            warnings.append(f"missing_mono:{row.get('phase')}:{row.get('event')}")
            continue
        if previous_mono is not None and mono == previous_mono:
            warnings.append("duplicate_monotonic_timestamp")
        previous_mono = mono

    markers = [{
        "phase": row.get("phase"),
        "event": row.get("event"),
        "subject": row.get("subject"),
        "uptime_ms": row.get("uptime_ms"),
        "process_cpu_ms": row.get("process_cpu_ms"),
        "gc_time_ms": row.get("gc_time_ms"),
        "heap_used_mib": row.get("heap_used_mib"),
        "available_memory_mib": row.get("available_memory_mib"),
    } for row in records]

    return {
        "valid": not invalid,
        "invalid_reasons": invalid,
        "warnings": warnings,
        "first_probe_uptime_ms": first_uptime,
        "jvm_start_epoch_ms": first.get("jvm_start_epoch_ms"),
        "main_menu_uptime_ms": next((row.get("uptime_ms") for row in records
                                      if row.get("phase") == "main_menu_opening" and row.get("event") == "point"), None),
        "scopes": scopes,
        "markers": markers,
        "semantics": {
            "wall": "scope elapsed from System.nanoTime; async/inclusive scopes may overlap and must not be summed",
            "process_cpu": "cumulative Minecraft JVM CPU across all JVM threads; not decoder or listener-exclusive CPU",
            "owner_thread_cpu": "only emitted as a delta when start/end execute on the same thread",
            "available_memory": "OS free/available physical memory snapshot; not a hard-fault or page-cache counter",
        },
    }


def analyze_file(path: Path, max_early_uptime_ms=60_000):
    lines = path.read_text(encoding="utf-8-sig", errors="replace").splitlines()
    return summarize(parse_lines(lines), max_early_uptime_ms=max_early_uptime_ms)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path, help="completed console/latest.log files; read only after Java exits")
    parser.add_argument("--max-early-uptime-ms", type=int, default=60_000)
    args = parser.parse_args()
    output = {str(path): analyze_file(path, args.max_early_uptime_ms) for path in args.logs}
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
