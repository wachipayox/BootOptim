#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TARGET = "net.minecraft.server.Bootstrap"
EXPECTED_REQUESTS = (
    {"origin": "securejar_get_maybe_transformed_bytes", "raw_context": "mixin", "effective_reason": "mixin", "caller": "org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode"},
    {"origin": "securejar_reader_to_class", "raw_context": "null", "effective_reason": "classloading", "caller": "net.minecraft.client.main.Main#lambda$main$0"},
)


def kv(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", line))


def parse(text: str) -> dict:
    requests: list[dict] = []
    request_ends: dict[int, dict] = {}
    transform_begins: dict[int, dict] = {}
    mixin_events: list[dict] = []
    main_events: list[dict] = []

    for line_no, line in enumerate(text.splitlines(), 1):
        if "BOOTOPTIM_ML_FORK_REQUEST_END " in line:
            item = kv(line)
            if item.get("class") == TARGET:
                rid = int(item["request_id"])
                request_ends[rid] = {**item, "line": line_no, "mono_ns": int(item["mono_ns"])}
        elif "BOOTOPTIM_ML_FORK_REQUEST " in line:
            item = kv(line)
            if item.get("class") == TARGET:
                requests.append({**item, "line": line_no, "request_id": int(item["request_id"])})
        elif "BOOTOPTIM_ML_FORK " in line:
            item = kv(line)
            if item.get("class") == TARGET and item.get("stage") == "class_transform_begin":
                rid = int(item["request_id"])
                transform_begins[rid] = {**item, "line": line_no, "mono_ns": int(item["mono_ns"])}
        elif "BOOTOPTIM_MIXIN_LIFECYCLE " in line:
            item = kv(line)
            if item.get("probe") == "agent96-mixin-main-lifecycle-v1":
                item["line"] = line_no
                item["mono_ns"] = int(item["mono_ns"])
                item["elapsed_ns"] = int(item.get("elapsed_ns", "0"))
                mixin_events.append(item)
        elif "BOOTOPTIM_MAIN_LIFECYCLE " in line:
            item = kv(line)
            item["line"] = line_no
            item["mono_ns"] = int(item["mono_ns"])
            main_events.append(item)

    if len(requests) != 2 or [r["request_id"] for r in requests] != [1, 2]:
        raise ValueError(f"expected Bootstrap requests [1,2] in order, found {[r['request_id'] for r in requests]}")
    for index, (request, expected) in enumerate(zip(requests, EXPECTED_REQUESTS), 1):
        for key, value in expected.items():
            if request.get(key) != value:
                raise ValueError(f"request {index} {key}={request.get(key)!r}, expected {value!r}")
        if request["request_id"] not in request_ends or request["request_id"] not in transform_begins:
            raise ValueError(f"request {index} missing end/transform marker")
        if request_ends[index].get("observed_id") != str(index):
            raise ValueError(f"request {index} stack identity mismatch")
    if requests[0].get("loader_id") != requests[1].get("loader_id") or requests[0].get("tccl_id") != requests[1].get("tccl_id"):
        raise ValueError("Bootstrap requests no longer share TransformingClassLoader/TCCL")
    if not (requests[0]["line"] < request_ends[1]["line"] < requests[1]["line"]):
        raise ValueError("request order is not completed Mixin request -> classloading request")

    r1_line = requests[0]["line"]
    r1_end = request_ends[1]["mono_ns"]
    r2_begin = transform_begins[2]["mono_ns"]

    def enclosing(enter_name: str, exit_name: str) -> tuple[dict, dict]:
        enters = [e for e in mixin_events if e.get("event") == enter_name and e["line"] < r1_line]
        if not enters:
            raise ValueError(f"request 1 not preceded by {enter_name}")
        enter = enters[-1]
        exit_event = next((e for e in mixin_events if e.get("event") == exit_name and e["line"] > r1_line), None)
        if exit_event is None or exit_event["line"] <= enter["line"]:
            raise ValueError(f"no enclosing {exit_name} for request 1")
        return enter, exit_event

    check_enter, check_exit = enclosing("check_select_enter", "check_select_exit")
    select_enter, select_exit = enclosing("select_enter", "select_exit")
    prepare_enter, prepare_exit = enclosing("prepare_configs_enter", "prepare_configs_exit")
    if not (check_enter["line"] < select_enter["line"] < prepare_enter["line"] < r1_line < prepare_exit["line"] < select_exit["line"] < check_exit["line"]):
        raise ValueError("Mixin lifecycle nesting around request 1 changed")

    required = [
        "main_entry",
        "before_shared_constants_version", "after_shared_constants_version",
        "before_datafixers_optimize", "after_datafixers_optimize",
        "before_crash_report_preload", "after_crash_report_preload",
        "main_before_run_and_tick", "bootstrap_worker_entry", "bootstrap_worker_return", "main_after_run_and_tick",
    ]
    main_by_name: dict[str, dict] = {}
    for name in required:
        matches = [e for e in main_events if e.get("event") == name]
        if len(matches) != 1:
            raise ValueError(f"expected exactly one Main lifecycle marker {name}, found {len(matches)}")
        main_by_name[name] = matches[0]

    main_entry = main_by_name["main_entry"]["mono_ns"]
    before_shared = main_by_name["before_shared_constants_version"]["mono_ns"]
    after_shared = main_by_name["after_shared_constants_version"]["mono_ns"]
    before_datafix = main_by_name["before_datafixers_optimize"]["mono_ns"]
    after_datafix = main_by_name["after_datafixers_optimize"]["mono_ns"]
    before_crash = main_by_name["before_crash_report_preload"]["mono_ns"]
    after_crash = main_by_name["after_crash_report_preload"]["mono_ns"]
    main_before = main_by_name["main_before_run_and_tick"]["mono_ns"]
    worker_entry = main_by_name["bootstrap_worker_entry"]["mono_ns"]
    worker_return = main_by_name["bootstrap_worker_return"]["mono_ns"]
    main_after = main_by_name["main_after_run_and_tick"]["mono_ns"]

    ordered = [
        r1_end, prepare_exit["mono_ns"], select_exit["mono_ns"], check_exit["mono_ns"],
        main_entry, before_shared, after_shared, before_datafix, after_datafix,
        before_crash, after_crash, main_before, worker_entry, r2_begin,
    ]
    if ordered != sorted(ordered):
        raise ValueError(f"causal ordering changed: {ordered}")
    if worker_return > main_after:
        raise ValueError("BackgroundWaiter returned before worker task completed")

    segments = {
        "request1_end_to_prepare_configs_exit": prepare_exit["mono_ns"] - r1_end,
        "prepare_configs_exit_to_select_exit": select_exit["mono_ns"] - prepare_exit["mono_ns"],
        "select_exit_to_check_select_exit": check_exit["mono_ns"] - select_exit["mono_ns"],
        "check_select_exit_to_main_entry": main_entry - check_exit["mono_ns"],
        "main_entry_to_shared_constants_version": before_shared - main_entry,
        "shared_constants_try_detect_version": after_shared - before_shared,
        "shared_constants_to_datafixers_optimize": before_datafix - after_shared,
        "datafixers_optimize_call": after_datafix - before_datafix,
        "datafixers_to_crash_report_preload": before_crash - after_datafix,
        "crash_report_preload_call": after_crash - before_crash,
        "crash_report_preload_to_background_waiter_call": main_before - after_crash,
        "background_waiter_call_to_worker_entry": worker_entry - main_before,
        "worker_entry_to_request2_transform_begin": r2_begin - worker_entry,
        "request1_end_to_request2_transform_begin": r2_begin - r1_end,
        "bootstrap_worker_task_wall": worker_return - worker_entry,
        "background_waiter_call_wall": main_after - main_before,
        "worker_return_to_background_waiter_return": main_after - worker_return,
    }

    post_checkselect_partition = {
        "pre_main_runtime_handoff": segments["check_select_exit_to_main_entry"],
        "main_option_fml_telemetry_prefix": segments["main_entry_to_shared_constants_version"],
        "shared_constants_try_detect_version": segments["shared_constants_try_detect_version"],
        "tracy_prefix_before_datafixers": segments["shared_constants_to_datafixers_optimize"],
        "datafixers_optimize_call": segments["datafixers_optimize_call"],
        "between_datafixers_and_crash_preload": segments["datafixers_to_crash_report_preload"],
        "crash_report_preload_call": segments["crash_report_preload_call"],
        "logger_stage_tail_before_background_waiter": segments["crash_report_preload_to_background_waiter_call"],
    }
    post_checkselect_wall = main_before - check_exit["mono_ns"]
    if sum(post_checkselect_partition.values()) != post_checkselect_wall:
        raise ValueError("post-checkSelect partition does not tile the serial wall")

    scopes = {
        "checkSelect": {"start_ns": check_enter["mono_ns"], "end_ns": check_exit["mono_ns"], "wall_ns": check_exit["mono_ns"] - check_enter["mono_ns"], "inclusive": True, "thread": check_enter.get("thread")},
        "select": {"start_ns": select_enter["mono_ns"], "end_ns": select_exit["mono_ns"], "wall_ns": select_exit["mono_ns"] - select_enter["mono_ns"], "inclusive": True, "thread": select_enter.get("thread")},
        "prepareConfigs": {"start_ns": prepare_enter["mono_ns"], "end_ns": prepare_exit["mono_ns"], "wall_ns": prepare_exit["mono_ns"] - prepare_enter["mono_ns"], "inclusive": True, "thread": prepare_enter.get("thread")},
    }

    dag = [
        {"id": "request1_mixin_bytes", "kind": "causal_request", "thread": requests[0].get("thread"), "predecessors": []},
        {"id": "prepare_configs_suffix", "kind": "serial_main_thread", "wall_ns": segments["request1_end_to_prepare_configs_exit"], "predecessors": ["request1_mixin_bytes"]},
        {"id": "select_tail", "kind": "serial_main_thread", "wall_ns": segments["prepare_configs_exit_to_select_exit"], "predecessors": ["prepare_configs_suffix"]},
        {"id": "check_select_tail", "kind": "serial_main_thread", "wall_ns": segments["select_exit_to_check_select_exit"], "predecessors": ["select_tail"]},
        {"id": "pre_main_runtime_handoff", "kind": "serial_boundary_residual", "wall_ns": segments["check_select_exit_to_main_entry"], "predecessors": ["check_select_tail"]},
        {"id": "main_prefix", "kind": "serial_game_fml_prefix", "wall_ns": main_before - main_entry, "predecessors": ["pre_main_runtime_handoff"]},
        {"id": "background_waiter_pre_worker", "kind": "submission_queue_window", "wall_ns": segments["background_waiter_call_to_worker_entry"], "predecessors": ["main_prefix"]},
        {"id": "bootstrap_worker", "kind": "concurrent_task", "wall_ns": segments["bootstrap_worker_task_wall"], "predecessors": ["background_waiter_pre_worker"]},
        {"id": "request2_classloading", "kind": "nested_in_bootstrap_worker", "thread": requests[1].get("thread"), "predecessors": ["bootstrap_worker"]},
        {"id": "background_waiter_completion_tail", "kind": "wait_cleanup_tail", "wall_ns": segments["worker_return_to_background_waiter_return"], "predecessors": ["bootstrap_worker"]},
    ]

    return {
        "schema": 5,
        "probe": "agent98-post-checkselect-main-prefix-v1",
        "metric_type": "single-run monotonic causal wall; callsite markers are observational; lifecycle scopes are inclusive; not A/B or a savings estimate",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "request_contract": "mixin_transformed_bytes_then_classloading",
        "same_loader_id": requests[0].get("loader_id"),
        "mixin_scopes_reused_unchanged_from_agent96": scopes,
        "segments_ns": segments,
        "post_checkselect_serial_wall_ns": post_checkselect_wall,
        "post_checkselect_partition_ns": post_checkselect_partition,
        "dag": dag,
        "classification_notes": {
            "pre_main_runtime_handoff": "Serial wall from the pre-existing checkSelect exit marker until the first executable instruction of transformed Main.main. Agent 98 does not instrument Mixin, so any remaining transformation/class-definition/launch work stays explicitly in this boundary residual.",
            "main_option_fml_telemetry_prefix": "Main runtime from method entry through option/FML hook parsing and game-load telemetry setup, ending immediately before SharedConstants.tryDetectVersion.",
            "datafixers_optimize_call": "Wall only across the synchronous DataFixers.optimize invocation returning its future; later asynchronous optimization work is not attributed here.",
            "submission_queue_window": "Marker is immediately before stock BackgroundWaiter.runAndTick; exact FML code performs progress/update/submission before the worker can enter. This bucket is not pure queue wait.",
        },
        "marker_counts": {"mixin": len(mixin_events), "main": len(main_events)},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = parse(args.console.read_text(encoding="utf-8", errors="replace"))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_AGENT98_MAIN_PREFIX_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
