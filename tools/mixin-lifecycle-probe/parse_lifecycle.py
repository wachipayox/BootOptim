#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TARGET = "net.minecraft.server.Bootstrap"
EXPECTED = (
    {"origin": "securejar_get_maybe_transformed_bytes", "raw_context": "mixin", "effective_reason": "mixin", "caller": "org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode"},
    {"origin": "securejar_reader_to_class", "raw_context": "null", "effective_reason": "classloading", "caller": "net.minecraft.client.main.Main#lambda$main$0"},
)


def kv(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", line))


def config_key(detail: str) -> str:
    return detail.split("#", 1)[0]


def merge_intervals(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[list[int]] = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if not merged or start > merged[-1][1]:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)
    return [(start, end) for start, end in merged]


def numeric(event: dict, key: str) -> bool:
    return event.get(key, "").isdigit()


def parse(text: str) -> dict:
    lines = text.splitlines()
    requests: list[dict] = []
    request_ends: dict[str, dict] = {}
    transform_begins: list[dict] = []
    mixin_events: list[dict] = []
    main_events: list[dict] = []

    for line_no, line in enumerate(lines, 1):
        if "BOOTOPTIM_ML_FORK_REQUEST " in line:
            item = kv(line); item["line"] = line_no; requests.append(item)
        elif "BOOTOPTIM_ML_FORK_REQUEST_END " in line:
            item = kv(line); item["line"] = line_no; request_ends[item["request_id"]] = item
        elif "BOOTOPTIM_ML_FORK " in line:
            item = kv(line)
            if item.get("class") == TARGET and item.get("stage") == "class_transform_begin":
                item["line"] = line_no; transform_begins.append(item)
        elif "BOOTOPTIM_MIXIN_LIFECYCLE " in line:
            item = kv(line); item["line"] = line_no; mixin_events.append(item)
        elif "BOOTOPTIM_MAIN_LIFECYCLE " in line:
            item = kv(line); item["line"] = line_no; main_events.append(item)

    if len(requests) != 2:
        raise ValueError(f"expected exactly two Bootstrap requests, found {len(requests)}")
    requests.sort(key=lambda item: int(item["request_id"]))
    for index, expected in enumerate(EXPECTED):
        request = requests[index]
        for key, value in expected.items():
            if request.get(key) != value:
                raise ValueError(f"request {index + 1} {key}={request.get(key)!r}, expected {value!r}")
        if request["request_id"] not in request_ends:
            raise ValueError(f"request {request['request_id']} missing end marker")
    if requests[0].get("loader_id") != requests[1].get("loader_id"):
        raise ValueError("known Bootstrap requests no longer use the same TransformingClassLoader")
    if requests[0].get("tccl_id") != requests[1].get("tccl_id"):
        raise ValueError("known Bootstrap requests no longer use the same TCCL")

    if len(transform_begins) != 2:
        raise ValueError(f"expected exactly two target ClassTransformer begins, found {len(transform_begins)}")
    transform_begins.sort(key=lambda item: int(item["mono_ns"]))
    for request, begin in zip(requests, transform_begins):
        end_line = request_ends[request["request_id"]]["line"]
        if not (request["line"] < begin["line"] < end_line):
            raise ValueError(f"request {request['request_id']} does not enclose its target transform")
        request["transform_begin_ns"] = int(begin["mono_ns"])
        request["end_ns"] = int(request_ends[request["request_id"]]["mono_ns"])

    r1_line = requests[0]["line"]
    open_config = None
    for event in mixin_events:
        if event["line"] >= r1_line:
            break
        if event.get("event") == "config_prepare_enter":
            open_config = event
        elif event.get("event") == "config_prepare_exit" and open_config is not None:
            if config_key(event.get("detail", "")) == config_key(open_config.get("detail", "")):
                open_config = None
    if open_config is None:
        raise ValueError("request 1 was not enclosed by config.prepare")
    config_name = config_key(open_config.get("detail", ""))
    config_exit = next((event for event in mixin_events if event["line"] > r1_line and event.get("event") == "config_prepare_exit" and config_key(event.get("detail", "")) == config_name), None)
    if config_exit is None:
        raise ValueError(f"enclosing config {config_name} has no exit marker")

    def first_event(name: str, after_line: int = 0) -> dict:
        event = next((item for item in mixin_events if item["line"] > after_line and item.get("event") == name), None)
        if event is None:
            raise ValueError(f"missing Mixin lifecycle event {name}")
        return event

    prepare_exit = first_event("prepare_configs_exit", r1_line)
    select_exit = first_event("select_exit", prepare_exit["line"])
    main_by_name = {item.get("event"): item for item in main_events}
    required_main = ["main_before_run_and_tick", "bootstrap_worker_entry", "bootstrap_worker_return", "main_after_run_and_tick"]
    missing = [name for name in required_main if name not in main_by_name]
    if missing:
        raise ValueError(f"missing Main lifecycle markers: {missing}")

    main_before_line = main_by_name["main_before_run_and_tick"]["line"]
    r1_end = requests[0]["end_ns"]
    r2_begin = requests[1]["transform_begin_ns"]
    config_exit_ns = int(config_exit["mono_ns"])
    prepare_exit_ns = int(prepare_exit["mono_ns"])
    select_exit_ns = int(select_exit["mono_ns"])
    main_before_ns = int(main_by_name["main_before_run_and_tick"]["mono_ns"])
    worker_entry_ns = int(main_by_name["bootstrap_worker_entry"]["mono_ns"])
    worker_return_ns = int(main_by_name["bootstrap_worker_return"]["mono_ns"])
    main_after_ns = int(main_by_name["main_after_run_and_tick"]["mono_ns"])
    ordered = [r1_end, config_exit_ns, prepare_exit_ns, select_exit_ns, main_before_ns, worker_entry_ns, r2_begin]
    if ordered != sorted(ordered):
        raise ValueError(f"causal marker ordering changed: {ordered}")

    # Console output from unrelated concurrent logging can corrupt diagnostic text.
    # Attribution only consumes applyMixins exits physically inside the causal
    # request1-end -> Main-submission window, and requires every such marker to
    # retain numeric mono/elapsed fields. Corruption outside the measured window
    # is irrelevant; corruption inside it invalidates the profile instead of
    # silently under-counting Mixin wall.
    apply_spans: list[dict] = []
    malformed_apply_in_window: list[dict] = []
    for event in mixin_events:
        if event.get("event") != "apply_mixins_exit":
            continue
        if not (request_ends[requests[0]["request_id"]]["line"] < event["line"] < main_before_line):
            continue
        if not numeric(event, "mono_ns") or not numeric(event, "elapsed_ns"):
            malformed_apply_in_window.append(event)
            continue
        end_ns = int(event["mono_ns"])
        wall_ns = int(event["elapsed_ns"])
        if wall_ns < 0:
            raise ValueError("negative applyMixins elapsed wall")
        start_ns = end_ns - wall_ns
        apply_spans.append({
            "thread": event.get("thread", "unknown"),
            "target": event.get("detail"),
            "start_ns": start_ns,
            "end_ns": end_ns,
            "wall_ns": wall_ns,
            "end_line": event["line"],
        })
    if malformed_apply_in_window:
        raise ValueError(f"malformed applyMixins marker(s) inside causal window: {malformed_apply_in_window[:3]}")

    clipped_apply = []
    for span in apply_spans:
        if span["thread"] != "main":
            continue
        start = max(r1_end, span["start_ns"])
        end = min(main_before_ns, span["end_ns"])
        if end > start:
            clipped_apply.append((start, end))
    merged_apply = merge_intervals(clipped_apply)
    mixin_apply_wall = sum(end - start for start, end in merged_apply)
    pre_submission_wall = main_before_ns - r1_end
    outside_apply_wall = pre_submission_wall - mixin_apply_wall
    if outside_apply_wall < 0:
        raise ValueError("merged Mixin apply wall exceeds causal pre-submission wall")

    later_configs = [
        item for item in mixin_events
        if item.get("event") == "config_prepare_exit" and config_exit["line"] < item["line"] < prepare_exit["line"]
    ]
    plugins = [
        item for item in mixin_events
        if item.get("event") == "plugin_accept_targets_exit" and config_exit["line"] < item["line"] < prepare_exit["line"]
    ]
    posts = [
        item for item in mixin_events
        if item.get("event") == "config_post_initialise_exit" and config_exit["line"] < item["line"] < prepare_exit["line"]
    ]

    callback_groups = {
        "config_prepare_after_enclosing_config": later_configs,
        "plugin_accept_targets_after_enclosing_config": plugins,
        "config_post_initialise_after_enclosing_config": posts,
    }
    callback_sums: dict[str, int] = {}
    callback_quality: dict[str, dict[str, int]] = {}
    for name, group in callback_groups.items():
        valid = [item for item in group if numeric(item, "elapsed_ns")]
        callback_sums[name] = sum(int(item["elapsed_ns"]) for item in valid)
        callback_quality[name] = {"total_markers": len(group), "numeric_markers": len(valid), "malformed_markers": len(group) - len(valid)}

    # These callback sums are supplemental nested diagnostics, not a causal
    # partition. If console interleaving damaged one marker, report the quality
    # rather than treating the partial sum as complete or failing the parent
    # lifecycle attribution.
    prepare_remainder_wall = prepare_exit_ns - config_exit_ns
    all_callback_complete = all(item["malformed_markers"] == 0 for item in callback_quality.values())
    if all_callback_complete:
        callback_sums["prepare_configs_wall_not_in_these_callbacks"] = prepare_remainder_wall - sum(callback_sums.values())
    top_configs = sorted(
        ({"config": config_key(item.get("detail", "")), "wall_ns": int(item["elapsed_ns"])} for item in later_configs if numeric(item, "elapsed_ns")),
        key=lambda item: item["wall_ns"], reverse=True,
    )[:10]

    partition = {
        "mixin_processor_apply_wall_request1_end_to_main_submission": mixin_apply_wall,
        "outside_mixin_processor_apply_wall_request1_end_to_main_submission": outside_apply_wall,
        "main_submission_to_worker_entry": worker_entry_ns - main_before_ns,
        "worker_entry_to_request2_transform_begin": r2_begin - worker_entry_ns,
    }
    if sum(partition.values()) != r2_begin - r1_end:
        raise ValueError("inter-request partition does not sum to total causal wall")

    return {
        "schema": 3,
        "metric_type": "single-run monotonic causal wall; applyMixins union is merged/non-overlapping; nested callback wall sums are supplemental and quality-labelled; not A/B",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "request_contract": requests,
        "same_loader_id": requests[0]["loader_id"],
        "enclosing_config": config_name,
        "segments_ns": {
            "request1_end_to_enclosing_config_prepare_exit": config_exit_ns - r1_end,
            "enclosing_config_prepare_exit_to_prepare_configs_exit": prepare_exit_ns - config_exit_ns,
            "prepare_configs_exit_to_select_exit": select_exit_ns - prepare_exit_ns,
            "select_exit_to_main_before_run_and_tick": main_before_ns - select_exit_ns,
            "main_before_run_and_tick_to_worker_entry": worker_entry_ns - main_before_ns,
            "worker_entry_to_request2_transform_begin": r2_begin - worker_entry_ns,
            "request1_end_to_request2_transform_begin": r2_begin - r1_end,
            "bootstrap_worker_entry_to_return": worker_return_ns - worker_entry_ns,
            "main_run_and_tick_call_wall": main_after_ns - main_before_ns,
        },
        "inter_request_partition_ns": partition,
        "nested_callback_wall_sums_ns": callback_sums,
        "nested_callback_marker_quality": callback_quality,
        "top_config_prepare_after_enclosing_config": top_configs,
        "mixin_apply_spans_in_gap": [
            {"start_ns": start, "end_ns": end, "wall_ns": end - start}
            for start, end in merged_apply
        ],
        "mixin_events": mixin_events,
        "main_events": main_events,
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
