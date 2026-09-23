"""Parse BOOTOPTIM_VARIANCE lines after Java exits; never poll latest.log during startup."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable

MARKER = "BOOTOPTIM_VARIANCE "
LISTENER_MARKER = "BOOTOPTIM_VARIANCE_LISTENER "
RELOAD_MARKER = "BOOTOPTIM_VARIANCE_RELOAD "
INT_FIELDS = {
    "seq", "scope", "mono_ns", "wall_epoch_ms", "jvm_start_epoch_ms", "uptime_ms",
    "thread_id", "gc_count", "gc_time_ms", "gc_count_delta", "gc_time_delta_ms",
}
FLOAT_FIELDS = {
    "process_cpu_ms", "thread_cpu_ms", "heap_used_mib", "heap_committed_mib",
    "heap_max_mib", "available_memory_mib", "elapsed_ms", "process_cpu_delta_ms",
    "owner_thread_cpu_delta_ms", "heap_used_delta_mib", "available_memory_delta_mib",
}
LISTENER_INT_FIELDS = {"reload_id", "index", "barrier_calls"}
LISTENER_FLOAT_FIELDS = {
    "prepare_done_ms", "apply_turn_ms", "complete_ms", "global_wait_ms", "order_wait_ms", "post_turn_ms",
}
RELOAD_INT_FIELDS = {"reload_id", "expected_listeners", "observed_listeners"}
RELOAD_FLOAT_FIELDS = {"all_preparations_ms", "all_done_ms"}
REQUIRED = {
    ("transformation_service_construct", "point"),
    ("root_mod_discovery", "end"),
    ("dependency_discovery", "end"),
    ("vanilla_bootstrap", "end"),
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
    ("main_menu_presented", "point"),
}
RESOURCE_SPLIT_REQUIRED = (REQUIRED - {("fancymenu_preload", "end")}) | {
    (phase, event)
    for phase in ("cit_active_load", "bakery_blockstate_registration", "bakery_parent_resolution",
                  "bakery_additional_model_event", "entity_provider_create", "player_provider_create",
                  "entity_add_layers_post")
    for event in ("start", "end")
}
RESOURCE_NEXT_REQUIRED = RESOURCE_SPLIT_REQUIRED | {
    (phase, event)
    for phase in ("bakery_post_blockstates_to_items", "bakery_vanilla_item_loop",
                  "bakery_additional_models_loop", "title_first_frame_blit",
                  "title_first_frame_display_update")
    for event in ("start", "end")
} | {("title_first_frame_render_return", "point"),
     ("startup_presented_screen", "point")}
MULTI_RELOAD_REQUIRED = RESOURCE_NEXT_REQUIRED | {
    ("manual_resource_pack_reload", "start"),
    ("manual_resource_pack_reload", "end"),
    ("reload_frame_presented", "point"),
    ("loading_overlay_exit_call", "point"),
}


def _parse_payload(payload: str, int_fields, float_fields):
    record = {}
    for token in payload.strip().split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        if key in int_fields:
            try:
                record[key] = int(value)
            except ValueError:
                record[key] = None
        elif key in float_fields:
            try:
                record[key] = float(value)
            except ValueError:
                record[key] = None
        else:
            record[key] = value
    return record


def parse_line(line: str):
    index = line.find(MARKER)
    if index < 0:
        return None
    record = _parse_payload(line[index + len(MARKER):], INT_FIELDS, FLOAT_FIELDS)
    return record if record.get("phase") and record.get("event") else None


def parse_listener_line(line: str):
    index = line.find(LISTENER_MARKER)
    if index < 0:
        return None
    record = _parse_payload(
        line[index + len(LISTENER_MARKER):], LISTENER_INT_FIELDS, LISTENER_FLOAT_FIELDS)
    return record if record.get("reload_id") is not None and record.get("index") is not None else None


def parse_reload_line(line: str):
    index = line.find(RELOAD_MARKER)
    if index < 0:
        return None
    record = _parse_payload(line[index + len(RELOAD_MARKER):], RELOAD_INT_FIELDS, RELOAD_FLOAT_FIELDS)
    return record if record.get("reload_id") is not None else None


def parse_lines(lines: Iterable[str]):
    return [record for line in lines if (record := parse_line(line)) is not None]


def parse_listener_lines(lines: Iterable[str]):
    return [record for line in lines if (record := parse_listener_line(line)) is not None]


def parse_reload_lines(lines: Iterable[str]):
    return [record for line in lines if (record := parse_reload_line(line)) is not None]


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


def _reload_id(subject):
    match = re.match(r"reload_(\d+)(?:_|$)", subject or "")
    return int(match.group(1)) if match else None


def _validate_multi_reload(records, listeners, reload_summaries, scope_warnings):
    invalid = []
    if scope_warnings:
        invalid.append("unbalanced_or_duplicate_scopes")
    starts = {}
    ends = {}
    frames = {}
    manual_starts = {}
    manual_ends = {}
    overlays = []
    for row in records:
        phase, event, mono = row.get("phase"), row.get("event"), row.get("mono_ns")
        if phase == "resource_reload":
            rid = _reload_id(row.get("subject"))
            target = starts if event == "start" else ends if event == "end" else None
            if rid is None or target is None or rid in target:
                invalid.append(f"ambiguous_resource_reload:{rid}:{event}")
            else:
                target[rid] = row
        elif phase == "reload_frame_presented" and event == "point":
            rid = _reload_id(row.get("subject"))
            if rid is None or rid in frames:
                invalid.append(f"ambiguous_reload_frame:{rid}")
            else:
                frames[rid] = row
        elif phase == "manual_resource_pack_reload":
            target = manual_starts if event == "start" else manual_ends if event == "end" else None
            if target is None or row.get("scope") in target:
                invalid.append(f"ambiguous_manual_request:{row.get('scope')}:{event}")
            else:
                target[row["scope"]] = row
        elif phase == "loading_overlay_exit_call" and event == "point":
            overlays.append(mono)

    ids = sorted(starts)
    if len(ids) < 3 or ids != list(range(1, len(ids) + 1)):
        invalid.append(f"expected_initial_changed_restored_reload_ids:{ids}")
    if set(ends) != set(starts) or set(frames) != set(starts):
        invalid.append("reload_start_end_frame_ids_differ")
    if set(manual_starts) != set(manual_ends) or len(manual_starts) < 2:
        invalid.append("missing_or_unpaired_manual_requests")

    summaries_by_id = {}
    for row in reload_summaries:
        rid = row["reload_id"]
        if rid in summaries_by_id:
            invalid.append(f"duplicate_reload_summary:{rid}")
        summaries_by_id[rid] = row
    if set(summaries_by_id) != set(starts):
        invalid.append("reload_summary_ids_differ")

    rows_by_id = {}
    for row in listeners:
        rows_by_id.setdefault(row["reload_id"], []).append(row)
    if set(rows_by_id) != set(starts):
        invalid.append("listener_reload_ids_differ")

    for rid in ids:
        start, end, frame = starts.get(rid), ends.get(rid), frames.get(rid)
        if start and end and frame:
            if not (start["mono_ns"] < end["mono_ns"] < frame["mono_ns"]):
                invalid.append(f"reload_order_invalid:{rid}")
            if "_result_success" not in (end.get("subject") or ""):
                invalid.append(f"reload_failed:{rid}")
            if rid > 1:
                owners = [scope for scope, manual in manual_starts.items()
                          if scope in manual_ends and manual["mono_ns"] <= start["mono_ns"]
                          and end["mono_ns"] <= manual_ends[scope]["mono_ns"]]
                if len(owners) != 1:
                    invalid.append(f"manual_request_owner_count:{rid}:{len(owners)}")
                if not any(end["mono_ns"] <= marker <= frame["mono_ns"] for marker in overlays):
                    invalid.append(f"overlay_exit_missing:{rid}")
        summary = summaries_by_id.get(rid)
        rows = rows_by_id.get(rid, [])
        if summary:
            expected = summary.get("expected_listeners")
            observed = summary.get("observed_listeners")
            if summary.get("result") != "success" or expected is None or expected < 1 \
                    or observed != expected or len(rows) != expected:
                invalid.append(f"reload_listener_count_or_result:{rid}")
        if sorted(row["index"] for row in rows) != list(range(len(rows))) or any(
                row.get("barrier_calls") != 1 or row.get("result") != "success"
                or row.get("turn_result") != "success" for row in rows):
            invalid.append(f"listener_lifecycle_invalid:{rid}")
    for earlier, later in zip(ids, ids[1:]):
        if earlier in ends and later in starts and ends[earlier]["mono_ns"] >= starts[later]["mono_ns"]:
            invalid.append(f"overlapping_reloads:{earlier}:{later}")
    return invalid


def summarize(records, max_early_uptime_ms=60_000, listeners=None, profile="legacy", reload_summaries=None):
    required = {"legacy": REQUIRED, "resource_split": RESOURCE_SPLIT_REQUIRED,
                "resource_next": RESOURCE_NEXT_REQUIRED,
                "multi_reload": MULTI_RELOAD_REQUIRED}[profile]
    records = sorted(records, key=lambda row: (row.get("mono_ns") is None, row.get("mono_ns") or 0))
    invalid = []
    warnings = []
    if not records:
        return {"valid": False, "invalid_reasons": ["no_variance_records"], "warnings": [], "scopes": [], "markers": [], "listeners": []}

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
    missing = sorted(required - present)
    invalid.extend(f"missing:{phase}:{event}" for phase, event in missing)

    menu_mono = next((row.get("mono_ns") for row in records
                      if row.get("phase") == "main_menu_opening" and row.get("event") == "point"), None)
    reload_starts = [row for row in records
                     if row.get("phase") == "resource_reload" and row.get("event") == "start"
                     and (menu_mono is None or row.get("mono_ns", 0) <= menu_mono)]
    if len(reload_starts) != 1:
        invalid.append(f"resource_reload_count_before_menu:{len(reload_starts)}")

    if listeners is not None and not listeners:
        invalid.append("missing_listener_lifecycle_rows")

    scopes, scope_warnings = _scope_summaries(records)
    warnings.extend(scope_warnings)
    if profile == "multi_reload":
        invalid.extend(_validate_multi_reload(records, listeners or [], reload_summaries or [], scope_warnings))

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
        "profile": profile,
        "valid": not invalid,
        "invalid_reasons": invalid,
        "warnings": warnings,
        "first_probe_uptime_ms": first_uptime,
        "jvm_start_epoch_ms": first.get("jvm_start_epoch_ms"),
        "main_menu_opening_uptime_ms": next((row.get("uptime_ms") for row in records
                                              if row.get("phase") == "main_menu_opening" and row.get("event") == "point"), None),
        "main_menu_presented_uptime_ms": next((row.get("uptime_ms") for row in records
                                                if row.get("phase") == "main_menu_presented" and row.get("event") == "point"), None),
        "scopes": scopes,
        "markers": markers,
        "listeners": [] if listeners is None else listeners,
        "reload_summaries": [] if reload_summaries is None else reload_summaries,
        "semantics": {
            "wall": "scope elapsed from System.nanoTime; async/inclusive scopes may overlap and must not be summed",
            "process_cpu": "cumulative Minecraft JVM CPU across all JVM threads; not decoder or listener-exclusive CPU",
            "owner_thread_cpu": "only emitted as a delta when start/end execute on the same thread",
            "listener_rows": "nanoTime-only barrier/turn/completion lifecycle captured in-memory and logged after a completed frame; rows are not task CPU",
            "available_memory": "OS free/available physical memory snapshot; not a hard-fault or page-cache counter",
        },
    }


def analyze_file(path: Path, max_early_uptime_ms=60_000, profile="legacy"):
    lines = path.read_text(encoding="utf-8-sig", errors="replace").splitlines()
    return summarize(
        parse_lines(lines),
        max_early_uptime_ms=max_early_uptime_ms,
        listeners=parse_listener_lines(lines),
        reload_summaries=parse_reload_lines(lines),
        profile=profile,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path, help="completed console/latest.log files; read only after Java exits")
    parser.add_argument("--max-early-uptime-ms", type=int, default=60_000)
    parser.add_argument("--profile", choices=("legacy", "resource_split", "resource_next", "multi_reload"), default="legacy",
                        help="resource_next also requires constructor and first-frame splits")
    args = parser.parse_args()
    output = {str(path): analyze_file(path, args.max_early_uptime_ms, args.profile) for path in args.logs}
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
