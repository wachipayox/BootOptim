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
                requests.append({**item, "line": line_no, "request_id": int(item["request_id"]), "mono_ns": int(item["mono_ns"])})
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

    main_by_name = {e.get("event"): e for e in main_events}
    required = ["main_before_run_and_tick", "bootstrap_worker_entry", "bootstrap_worker_return", "main_after_run_and_tick"]
    missing = [name for name in required if name not in main_by_name]
    if missing:
        raise ValueError(f"missing Main lifecycle markers: {missing}")
    main_before = main_by_name["main_before_run_and_tick"]["mono_ns"]
    worker_entry = main_by_name["bootstrap_worker_entry"]["mono_ns"]
    worker_return = main_by_name["bootstrap_worker_return"]["mono_ns"]
    main_after = main_by_name["main_after_run_and_tick"]["mono_ns"]

    ordered = [r1_end, prepare_exit["mono_ns"], select_exit["mono_ns"], check_exit["mono_ns"], main_before, worker_entry, r2_begin]
    if ordered != sorted(ordered):
        raise ValueError(f"causal ordering changed: {ordered}")
    if worker_return > main_after:
        raise ValueError("BackgroundWaiter returned before worker task completed")

    segments = {
        "request1_end_to_prepare_configs_exit": prepare_exit["mono_ns"] - r1_end,
        "prepare_configs_exit_to_select_exit": select_exit["mono_ns"] - prepare_exit["mono_ns"],
        "select_exit_to_check_select_exit": check_exit["mono_ns"] - select_exit["mono_ns"],
        "check_select_exit_to_background_waiter_call": main_before - check_exit["mono_ns"],
        "background_waiter_call_to_worker_entry": worker_entry - main_before,
        "worker_entry_to_request2_transform_begin": r2_begin - worker_entry,
        "request1_end_to_request2_transform_begin": r2_begin - r1_end,
        "bootstrap_worker_task_wall": worker_return - worker_entry,
        "background_waiter_call_wall": main_after - main_before,
        "worker_return_to_background_waiter_return": main_after - worker_return,
    }
    inter_request_partition = {
        "mixin_prepare_configs_suffix_main_thread": segments["request1_end_to_prepare_configs_exit"],
        "mixin_select_tail_main_thread": segments["prepare_configs_exit_to_select_exit"],
        "mixin_check_select_tail_main_thread": segments["select_exit_to_check_select_exit"],
        "unknown_main_thread_after_check_select": segments["check_select_exit_to_background_waiter_call"],
        "background_waiter_pre_worker_submission_queue_window": segments["background_waiter_call_to_worker_entry"],
        "bootstrap_worker_preamble": segments["worker_entry_to_request2_transform_begin"],
    }
    if sum(inter_request_partition.values()) != segments["request1_end_to_request2_transform_begin"]:
        raise ValueError("inter-request causal partition does not tile the wall")

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
        {"id": "unknown_main_thread", "kind": "unknown_serial_work", "wall_ns": segments["check_select_exit_to_background_waiter_call"], "predecessors": ["check_select_tail"]},
        {"id": "background_waiter_pre_worker", "kind": "submission_queue_window", "wall_ns": segments["background_waiter_call_to_worker_entry"], "predecessors": ["unknown_main_thread"]},
        {"id": "bootstrap_worker", "kind": "concurrent_task", "wall_ns": segments["bootstrap_worker_task_wall"], "predecessors": ["background_waiter_pre_worker"]},
        {"id": "request2_classloading", "kind": "nested_in_bootstrap_worker", "thread": requests[1].get("thread"), "predecessors": ["bootstrap_worker"]},
        {"id": "background_waiter_completion_tail", "kind": "wait_cleanup_tail", "wall_ns": segments["worker_return_to_background_waiter_return"], "predecessors": ["bootstrap_worker"]},
    ]

    return {
        "schema": 4,
        "metric_type": "single-run monotonic causal wall; lifecycle scopes are inclusive; BackgroundWaiter worker and main wait loop overlap; not A/B or a savings estimate",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "request_contract": "mixin_transformed_bytes_then_classloading",
        "same_loader_id": requests[0].get("loader_id"),
        "mixin_scopes": scopes,
        "segments_ns": segments,
        "inter_request_partition_ns": inter_request_partition,
        "dag": dag,
        "classification_notes": {
            "submission_queue_window": "Marker is immediately before stock BackgroundWaiter.runAndTick; exact FML code performs updateProgress then runner.submit before the worker can enter. This bucket is inclusive and is not pure queue wait.",
            "concurrent_task": "bootstrap_worker_task_wall runs on the stock single-thread executor while Main remains inside BackgroundWaiter tick/sleep waiting.",
            "wait_cleanup_tail": "Worker return to runAndTick return is stock completion observation/sleep tail plus runner.shutdown/work.get; it is not worker CPU.",
            "unknown_serial_work": "Main-thread wall after checkSelect exits and before the BackgroundWaiter call. No owner is inferred by subtraction.",
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
    print("BOOTOPTIM_MIXIN_MAIN_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
