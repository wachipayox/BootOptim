#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

MARKER = "BOOTOPTIM_MAIN_LIFECYCLE "
REQUIRED = (
    "before_datafixers_optimize",
    "datafixers_clinit_entry",
    "datafixers_before_create_fixer_upper_call",
    "create_fixer_upper_entry",
    "create_fixer_upper_return",
    "datafixers_after_create_fixer_upper_call",
    "datafixers_clinit_exit",
    "datafixers_optimize_entry",
    "datafixers_before_executor_create",
    "datafixers_after_executor_create",
    "datafixers_before_result_get",
    "datafixers_after_result_get",
    "datafixers_before_result_optimize",
    "datafixers_after_result_optimize",
    "datafixers_optimize_return",
    "after_datafixers_optimize",
    "before_datafixer_join",
    "after_datafixer_join",
)
EVENT_RE = re.compile(r"^event=([a-z0-9_]+)(?:\s|$)")
RECORD_RE = re.compile(
    r"^event=(?P<event>[a-z0-9_]+)\s+"
    r"mono_ns=(?P<mono_ns>\d+)\s+"
    r"thread=(?P<thread>[^\s]+)\s+"
    r"tccl=(?P<tccl>[^\s]+)\s+"
    r"tccl_id=(?P<tccl_id>\d+)(?:\s|$)"
)


def parse(text: str) -> dict:
    events: list[dict] = []
    required_set = set(REQUIRED)
    for line_no, line in enumerate(text.splitlines(), 1):
        marker_at = line.find(MARKER)
        if marker_at < 0:
            continue
        payload = line[marker_at + len(MARKER):]
        event_match = EVENT_RE.match(payload)
        if event_match is None or event_match.group(1) not in required_set:
            # Worker/non-Agent-99 lifecycle markers may share stderr with ordinary logging.
            # They are not causal inputs to this parser, so never parse their numeric fields.
            continue
        event = event_match.group(1)
        record = RECORD_RE.match(payload)
        if record is None:
            raise ValueError(f"required DataFixers marker {event} is malformed at line {line_no}")
        item = record.groupdict()
        item["line"] = line_no
        item["mono_ns"] = int(item["mono_ns"])
        item["tccl_id"] = int(item["tccl_id"])
        events.append(item)

    by_name: dict[str, dict] = {}
    for name in REQUIRED:
        matches = [event for event in events if event["event"] == name]
        if len(matches) != 1:
            raise ValueError(f"expected exactly one {name}, found {len(matches)}")
        by_name[name] = matches[0]

    ordered = [by_name[name]["mono_ns"] for name in REQUIRED]
    if ordered != sorted(ordered):
        raise ValueError("DataFixers lifecycle markers are not in the pinned 1.21.1 order")
    threads = {by_name[name]["thread"] for name in REQUIRED}
    if threads != {"main"}:
        raise ValueError(f"DataFixers boundary markers changed thread: {sorted(threads)}")
    tccl_ids = {by_name[name]["tccl_id"] for name in REQUIRED}
    if len(tccl_ids) != 1:
        raise ValueError(f"DataFixers boundary markers changed TCCL identity: {sorted(tccl_ids)}")

    n = {name: by_name[name]["mono_ns"] for name in REQUIRED}
    segments = {
        "main_invoke_to_clinit_entry": n["datafixers_clinit_entry"] - n["before_datafixers_optimize"],
        "clinit_prefix_before_create": n["datafixers_before_create_fixer_upper_call"] - n["datafixers_clinit_entry"],
        "create_call_entry_boundary": n["create_fixer_upper_entry"] - n["datafixers_before_create_fixer_upper_call"],
        "create_fixer_upper_body": n["create_fixer_upper_return"] - n["create_fixer_upper_entry"],
        "create_call_return_boundary": n["datafixers_after_create_fixer_upper_call"] - n["create_fixer_upper_return"],
        "clinit_suffix_after_create": n["datafixers_clinit_exit"] - n["datafixers_after_create_fixer_upper_call"],
        "clinit_exit_to_optimize_entry": n["datafixers_optimize_entry"] - n["datafixers_clinit_exit"],
        "optimize_prefix_before_executor": n["datafixers_before_executor_create"] - n["datafixers_optimize_entry"],
        "executor_create_call": n["datafixers_after_executor_create"] - n["datafixers_before_executor_create"],
        "executor_to_result_get": n["datafixers_before_result_get"] - n["datafixers_after_executor_create"],
        "result_getstatic": n["datafixers_after_result_get"] - n["datafixers_before_result_get"],
        "result_get_to_submission": n["datafixers_before_result_optimize"] - n["datafixers_after_result_get"],
        "result_optimize_submission_call": n["datafixers_after_result_optimize"] - n["datafixers_before_result_optimize"],
        "submission_return_to_optimize_return": n["datafixers_optimize_return"] - n["datafixers_after_result_optimize"],
        "optimize_return_to_main_after": n["after_datafixers_optimize"] - n["datafixers_optimize_return"],
    }
    call_wall = n["after_datafixers_optimize"] - n["before_datafixers_optimize"]
    if sum(segments.values()) != call_wall:
        raise ValueError("DataFixers optimize-call partition does not tile the observed Main wall")

    clinit_wall = n["datafixers_clinit_exit"] - n["datafixers_clinit_entry"]
    create_body = segments["create_fixer_upper_body"]
    optimize_body = n["datafixers_optimize_return"] - n["datafixers_optimize_entry"]
    join_wall = n["after_datafixer_join"] - n["before_datafixer_join"]
    overlap_window = n["before_datafixer_join"] - n["after_datafixers_optimize"]
    clinit_residual = clinit_wall - create_body
    optimize_body_known_calls = (
        segments["executor_create_call"]
        + segments["result_getstatic"]
        + segments["result_optimize_submission_call"]
    )
    optimize_body_residual = optimize_body - optimize_body_known_calls

    return {
        "schema": 2,
        "probe": "agent99-datafixers-lifecycle-v1",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "metric_type": "single-run monotonic observational wall; no A/B and no savings estimate",
        "pinned_contract": "Minecraft 1.21.1 DataFixers static Result + single-thread daemon executor + Main CompletableFuture.join",
        "main_thread": "main",
        "main_tccl_id": next(iter(tccl_ids)),
        "datafixers_main_call_wall_ns": call_wall,
        "clinit_wall_ns": clinit_wall,
        "create_fixer_upper_body_ns": create_body,
        "clinit_residual_outside_create_body_ns": clinit_residual,
        "optimize_method_body_ns": optimize_body,
        "optimize_known_calls_ns": optimize_body_known_calls,
        "optimize_residual_outside_known_calls_ns": optimize_body_residual,
        "future_overlap_window_before_join_ns": overlap_window,
        "future_join_wait_ns": join_wall,
        "segments_ns": segments,
        "dag": [
            {"id": "main_before_datafixers", "kind": "serial_boundary", "predecessors": []},
            {"id": "datafixers_clinit", "kind": "serial_class_initialization", "wall_ns": clinit_wall, "predecessors": ["main_before_datafixers"]},
            {"id": "create_fixer_upper", "kind": "nested_serial_build", "wall_ns": create_body, "predecessors": ["datafixers_clinit"]},
            {"id": "executor_create", "kind": "serial_call", "wall_ns": segments["executor_create_call"], "predecessors": ["datafixers_clinit"]},
            {"id": "result_get", "kind": "serial_static_field_read", "wall_ns": segments["result_getstatic"], "predecessors": ["executor_create"]},
            {"id": "result_optimize_submission", "kind": "serial_submission_call", "wall_ns": segments["result_optimize_submission_call"], "predecessors": ["result_get"]},
            {"id": "datafixer_async_work", "kind": "concurrent_uninstrumented_stock_future", "start": "after_result_optimize", "completion": "unknown_but_no_later_than_join_return", "predecessors": ["result_optimize_submission"]},
            {"id": "main_overlap_window", "kind": "serial_main_work_concurrent_with_future", "wall_ns": overlap_window, "predecessors": ["result_optimize_submission"]},
            {"id": "datafixer_join", "kind": "future_wait", "wall_ns": join_wall, "predecessors": ["main_overlap_window", "datafixer_async_work"]},
        ],
        "classification_notes": {
            "clinit": "The Main call triggers normal JVM class initialization. Hooks are inside the transformed DataFixers class; no earlier Class.forName/getDataFixer access is introduced.",
            "submission": "The Result.optimize invocation is bounded as the stock synchronous submission/future-construction call. No executor/future wrapper or callback is attached.",
            "async_work": "No completion callback is added, so asynchronous optimization duration is intentionally not claimed. The only causal wait observed is the original Main CompletableFuture.join call.",
            "residual": "Residuals include bytecode/JVM boundary overhead and ordinary method code outside the named calls; they are explicit rather than assigned by subtraction to a speculative owner.",
            "stderr_interleaving": "Only the 18 required Agent 99 main-thread payloads are parsed. Unrelated lifecycle records are ignored before numeric conversion; a malformed required payload still fails closed.",
        },
        "required_marker_count": len(events),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = parse(args.console.read_text(encoding="utf-8", errors="replace"))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_AGENT99_DATAFIXERS_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
