#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TARGET = "net.minecraft.server.Bootstrap"
PROBE = "agent96-mixin-prepareconfigs-suffix-v2"
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
            if item.get("probe") == PROBE:
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

    def unique(name: str) -> dict:
        matches = [e for e in mixin_events if e.get("event") == name]
        if len(matches) != 1:
            raise ValueError(f"expected exactly one {name}, found {len(matches)}")
        return matches[0]

    check_enter, check_exit = enclosing("check_select_enter", "check_select_exit")
    select_enter, select_exit = enclosing("select_enter", "select_exit")
    prepare_enter, prepare_exit = enclosing("prepare_configs_enter", "prepare_configs_exit")

    listener_enter = unique("listener_registration_enter")
    listener_exit = unique("listener_registration_exit")
    config_prepare_enter = unique("config_prepare_enter")
    config_prepare_exit = unique("config_prepare_exit")
    plugin_enter = unique("plugin_accept_targets_enter")
    plugin_exit = unique("plugin_accept_targets_exit")
    post_enter = unique("post_initialise_enter")
    post_exit = unique("post_initialise_exit")
    commit_enter = unique("config_commit_enter")
    commit_exit = unique("config_commit_exit")

    lifecycle_lines = [
        check_enter["line"], select_enter["line"], prepare_enter["line"],
        listener_enter["line"], listener_exit["line"], config_prepare_enter["line"],
        r1_line, config_prepare_exit["line"], plugin_enter["line"], plugin_exit["line"],
        post_enter["line"], post_exit["line"], commit_enter["line"], commit_exit["line"],
        prepare_exit["line"], select_exit["line"], check_exit["line"],
    ]
    if lifecycle_lines != sorted(lifecycle_lines):
        raise ValueError(f"Mixin coarse lifecycle nesting/order changed: {lifecycle_lines}")

    main_by_name = {e.get("event"): e for e in main_events}
    required = ["main_before_run_and_tick", "bootstrap_worker_entry", "bootstrap_worker_return", "main_after_run_and_tick"]
    missing = [name for name in required if name not in main_by_name]
    if missing:
        raise ValueError(f"missing Main lifecycle markers: {missing}")
    main_before = main_by_name["main_before_run_and_tick"]["mono_ns"]
    worker_entry = main_by_name["bootstrap_worker_entry"]["mono_ns"]
    worker_return = main_by_name["bootstrap_worker_return"]["mono_ns"]
    main_after = main_by_name["main_after_run_and_tick"]["mono_ns"]

    ordered = [r1_end, config_prepare_exit["mono_ns"], plugin_enter["mono_ns"], plugin_exit["mono_ns"], post_enter["mono_ns"], post_exit["mono_ns"], commit_enter["mono_ns"], commit_exit["mono_ns"], prepare_exit["mono_ns"], select_exit["mono_ns"], check_exit["mono_ns"], main_before, worker_entry, r2_begin]
    if ordered != sorted(ordered):
        raise ValueError(f"causal ordering changed: {ordered}")
    if worker_return > main_after:
        raise ValueError("BackgroundWaiter returned before worker task completed")

    def scope(start: dict, end: dict, *, inclusive: bool = False) -> dict:
        return {
            "start_ns": start["mono_ns"],
            "end_ns": end["mono_ns"],
            "wall_ns": end["mono_ns"] - start["mono_ns"],
            "inclusive": inclusive,
            "thread": start.get("thread"),
        }

    coarse_scopes = {
        "listener_registration": scope(listener_enter, listener_exit),
        "config_prepare": scope(config_prepare_enter, config_prepare_exit),
        "plugin_accept_targets": scope(plugin_enter, plugin_exit),
        "post_initialise": scope(post_enter, post_exit),
        "config_commit": scope(commit_enter, commit_exit),
    }

    prepare_suffix = prepare_exit["mono_ns"] - r1_end
    suffix_partition = {
        "config_prepare_remaining_after_request1": config_prepare_exit["mono_ns"] - r1_end,
        "boundary_residual_prepare_to_plugin": plugin_enter["mono_ns"] - config_prepare_exit["mono_ns"],
        "plugin_accept_targets_callbacks": plugin_exit["mono_ns"] - plugin_enter["mono_ns"],
        "boundary_residual_plugin_to_post_initialise": post_enter["mono_ns"] - plugin_exit["mono_ns"],
        "post_initialise_mutable_validation_callbacks": post_exit["mono_ns"] - post_enter["mono_ns"],
        "boundary_residual_post_initialise_to_commit": commit_enter["mono_ns"] - post_exit["mono_ns"],
        "config_commit_add_sort_clear": commit_exit["mono_ns"] - commit_enter["mono_ns"],
        "boundary_residual_commit_to_prepare_configs_exit": prepare_exit["mono_ns"] - commit_exit["mono_ns"],
    }
    if any(value < 0 for value in suffix_partition.values()):
        raise ValueError(f"negative prepareConfigs suffix segment: {suffix_partition}")
    if sum(suffix_partition.values()) != prepare_suffix:
        raise ValueError("prepareConfigs suffix partition does not tile request1_end -> prepareConfigs_exit")

    segments = {
        "request1_end_to_prepare_configs_exit": prepare_suffix,
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
        "checkSelect": scope(check_enter, check_exit, inclusive=True),
        "select": scope(select_enter, select_exit, inclusive=True),
        "prepareConfigs": scope(prepare_enter, prepare_exit, inclusive=True),
    }

    dag = [
        {"id": "request1_mixin_bytes", "kind": "causal_request", "thread": requests[0].get("thread"), "predecessors": []},
        {"id": "config_prepare_remaining", "kind": "serial_main_thread", "wall_ns": suffix_partition["config_prepare_remaining_after_request1"], "predecessors": ["request1_mixin_bytes"]},
        {"id": "plugin_accept_targets", "kind": "ordered_callback_state", "wall_ns": suffix_partition["plugin_accept_targets_callbacks"], "predecessors": ["config_prepare_remaining"]},
        {"id": "post_initialise", "kind": "ordered_mutable_validation_callbacks", "wall_ns": suffix_partition["post_initialise_mutable_validation_callbacks"], "predecessors": ["plugin_accept_targets"]},
        {"id": "config_commit", "kind": "ordered_state_commit", "wall_ns": suffix_partition["config_commit_add_sort_clear"], "predecessors": ["post_initialise"]},
        {"id": "prepare_configs_boundary_residual", "kind": "explicit_probe_boundary_residual", "wall_ns": sum(value for key, value in suffix_partition.items() if key.startswith("boundary_residual_")), "predecessors": ["config_commit"]},
        {"id": "select_tail", "kind": "serial_main_thread", "wall_ns": segments["prepare_configs_exit_to_select_exit"], "predecessors": ["prepare_configs_boundary_residual"]},
        {"id": "check_select_tail", "kind": "serial_main_thread", "wall_ns": segments["select_exit_to_check_select_exit"], "predecessors": ["select_tail"]},
        {"id": "unknown_main_thread", "kind": "unknown_serial_work", "wall_ns": segments["check_select_exit_to_background_waiter_call"], "predecessors": ["check_select_tail"]},
        {"id": "background_waiter_pre_worker", "kind": "submission_queue_window", "wall_ns": segments["background_waiter_call_to_worker_entry"], "predecessors": ["unknown_main_thread"]},
        {"id": "bootstrap_worker", "kind": "concurrent_task", "wall_ns": segments["bootstrap_worker_task_wall"], "predecessors": ["background_waiter_pre_worker"]},
        {"id": "request2_classloading", "kind": "nested_in_bootstrap_worker", "thread": requests[1].get("thread"), "predecessors": ["bootstrap_worker"]},
        {"id": "background_waiter_completion_tail", "kind": "wait_cleanup_tail", "wall_ns": segments["worker_return_to_background_waiter_return"], "predecessors": ["bootstrap_worker"]},
    ]

    return {
        "schema": 5,
        "metric_type": "single-run monotonic causal wall; checkSelect/select/prepareConfigs scopes are inclusive; coarse prepareConfigs subscopes are non-overlapping method-block walls; not A/B or a savings estimate",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "request_contract": "mixin_transformed_bytes_then_classloading",
        "same_loader_id": requests[0].get("loader_id"),
        "mixin_scopes": scopes,
        "prepare_configs_coarse_scopes": coarse_scopes,
        "prepare_configs_suffix_partition_ns": suffix_partition,
        "segments_ns": segments,
        "inter_request_partition_ns": inter_request_partition,
        "dag": dag,
        "classification_notes": {
            "config_prepare": "MixinConfig.prepare mutates prepared/global mixin/pending/target/listener-visible state while constructing and parsing MixinInfo; do not treat this whole scope as pure work.",
            "plugin_accept_targets_callbacks": "Calls third-party IMixinConfigPlugin.acceptTargets in pending-config order. Callback behavior and state are observable and must not be moved or parallelised.",
            "post_initialise": "MixinConfig.postInitialise may call plugin.getMixins, prepare companion mixins, validate each MixinInfo, invoke listeners.onInit, and remove invalid mixins/mappings. Ordered mutable state.",
            "config_commit": "Stock publication step: add pending configs, sort configs, clear pending configs. This is an ordered state commit, not a preparation candidate.",
            "boundary_residual": "Explicit nanosecond gaps between adjacent coarse trace calls plus prepareConfigs return bookkeeping; retained so the suffix tiles exactly rather than attributing by subtraction.",
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
    print("BOOTOPTIM_MIXIN_PREPARECONFIGS_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
