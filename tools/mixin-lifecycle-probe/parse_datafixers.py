#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

MARKER = "BOOTOPTIM_MAIN_LIFECYCLE "
SUMMARY = "BOOTOPTIM_DATAFIXER_CREATE_SUBPHASES "
REQUIRED = (
    "before_datafixers_optimize",
    "datafixers_clinit_entry",
    "datafixers_before_create_fixer_upper_call",
    "create_fixer_upper_entry",
    "create_before_builder_constructor",
    "create_after_builder_constructor",
    "create_before_add_fixers",
    "create_before_second_schema_add",
    "create_after_second_schema_add",
    "create_after_add_fixers",
    "create_before_builder_build",
    "create_after_builder_build",
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
SUMMARY_RE = re.compile(
    r"^schema_calls=(?P<schema_calls>\d+)\s+"
    r"schema_total_ns=(?P<schema_total_ns>\d+)\s+"
    r"schema_first_ns=(?P<schema_first_ns>\d+)\s+"
    r"schema_second_ns=(?P<schema_second_ns>\d+)\s+"
    r"schema_rest_ns=(?P<schema_rest_ns>\d+)\s+"
    r"fixer_register_calls=(?P<fixer_register_calls>\d+)\s+"
    r"fixer_register_total_ns=(?P<fixer_register_total_ns>\d+)\s+"
    r"thread=(?P<thread>[^\s]+)\s+"
    r"tccl=(?P<tccl>[^\s]+)\s+"
    r"tccl_id=(?P<tccl_id>\d+)(?:\s|$)"
)


def parse(text: str) -> dict:
    events: list[dict] = []
    summaries: list[dict] = []
    datafixerupper_init_lines: list[int] = []
    required_set = set(REQUIRED)

    for line_no, line in enumerate(text.splitlines(), 1):
        marker_at = line.find(MARKER)
        if marker_at >= 0:
            payload = line[marker_at + len(MARKER):]
            event_match = EVENT_RE.match(payload)
            if event_match is not None and event_match.group(1) in required_set:
                event = event_match.group(1)
                record = RECORD_RE.match(payload)
                if record is None:
                    raise ValueError(f"required DataFixers marker {event} is malformed at line {line_no}")
                item = record.groupdict()
                item["line"] = line_no
                item["mono_ns"] = int(item["mono_ns"])
                item["tccl_id"] = int(item["tccl_id"])
                events.append(item)

        summary_at = line.find(SUMMARY)
        if summary_at >= 0:
            record = SUMMARY_RE.match(line[summary_at + len(SUMMARY):])
            if record is None:
                raise ValueError(f"malformed DataFixer create summary at line {line_no}")
            item = record.groupdict()
            item["line"] = line_no
            for key in (
                "schema_calls",
                "schema_total_ns",
                "schema_first_ns",
                "schema_second_ns",
                "schema_rest_ns",
                "fixer_register_calls",
                "fixer_register_total_ns",
                "tccl_id",
            ):
                item[key] = int(item[key])
            summaries.append(item)

        if "[class,init]" in line and "Initializing 'com/mojang/datafixers/DataFixerUpper'" in line:
            datafixerupper_init_lines.append(line_no)

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

    if len(summaries) != 1:
        raise ValueError(f"expected exactly one DataFixer create subphase summary, found {len(summaries)}")
    summary = summaries[0]
    if summary["thread"] != "main" or summary["tccl_id"] != next(iter(tccl_ids)):
        raise ValueError("DataFixer create summary changed thread/TCCL identity")
    if summary["schema_calls"] < 100 or summary["fixer_register_calls"] < 100:
        raise ValueError("DataFixer addFixers mutation counts do not match the pinned large 1.21.1 topology")
    if summary["schema_first_ns"] + summary["schema_second_ns"] + summary["schema_rest_ns"] != summary["schema_total_ns"]:
        raise ValueError("schema-call aggregate does not tile first + second + rest")

    if len(datafixerupper_init_lines) != 1:
        raise ValueError(f"expected exactly one JVM DataFixerUpper initialization record, found {len(datafixerupper_init_lines)}")
    init_line = datafixerupper_init_lines[0]
    second_before_line = by_name["create_before_second_schema_add"]["line"]
    second_after_line = by_name["create_after_second_schema_add"]["line"]
    if not (second_before_line < init_line < second_after_line):
        raise ValueError("DataFixerUpper JVM initialization was not nested in the second addSchema call")

    n = {name: by_name[name]["mono_ns"] for name in REQUIRED}
    create_segments = {
        "create_prefix_version_and_allocation": n["create_before_builder_constructor"] - n["create_fixer_upper_entry"],
        "builder_constructor_call": n["create_after_builder_constructor"] - n["create_before_builder_constructor"],
        "builder_ctor_to_add_fixers": n["create_before_add_fixers"] - n["create_after_builder_constructor"],
        "add_fixers_call": n["create_after_add_fixers"] - n["create_before_add_fixers"],
        "add_fixers_to_build": n["create_before_builder_build"] - n["create_after_add_fixers"],
        "builder_build_call": n["create_after_builder_build"] - n["create_before_builder_build"],
        "build_to_create_return": n["create_fixer_upper_return"] - n["create_after_builder_build"],
    }
    create_body = n["create_fixer_upper_return"] - n["create_fixer_upper_entry"]
    if sum(create_segments.values()) != create_body:
        raise ValueError("createFixerUpper inner partition does not tile the observed body")

    add_fixers_wall = create_segments["add_fixers_call"]
    add_fixers_known_calls = summary["schema_total_ns"] + summary["fixer_register_total_ns"]
    add_fixers_residual = add_fixers_wall - add_fixers_known_calls
    if add_fixers_residual < 0:
        raise ValueError("aggregate schema/fixer call timing exceeds enclosing addFixers wall")

    outer_segments = {
        "main_invoke_to_clinit_entry": n["datafixers_clinit_entry"] - n["before_datafixers_optimize"],
        "clinit_prefix_before_create": n["datafixers_before_create_fixer_upper_call"] - n["datafixers_clinit_entry"],
        "create_call_entry_boundary": n["create_fixer_upper_entry"] - n["datafixers_before_create_fixer_upper_call"],
        "create_fixer_upper_body": create_body,
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
    if sum(outer_segments.values()) != call_wall:
        raise ValueError("DataFixers optimize-call partition does not tile the observed Main wall")

    clinit_wall = n["datafixers_clinit_exit"] - n["datafixers_clinit_entry"]
    optimize_body = n["datafixers_optimize_return"] - n["datafixers_optimize_entry"]
    join_wall = n["after_datafixer_join"] - n["before_datafixer_join"]
    overlap_window = n["before_datafixer_join"] - n["after_datafixers_optimize"]
    clinit_residual = clinit_wall - create_body
    optimize_body_known_calls = (
        outer_segments["executor_create_call"]
        + outer_segments["result_getstatic"]
        + outer_segments["result_optimize_submission_call"]
    )
    optimize_body_residual = optimize_body - optimize_body_known_calls

    return {
        "schema": 3,
        "probe": "agent102-datafixers-create-subphases-v1",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "metric_type": "single-run monotonic observational wall; no A/B and no savings estimate",
        "pinned_contract": "Minecraft 1.21.1 DataFixers + DFU 8.0.16 mutation topology; JVM class-init log required",
        "main_thread": "main",
        "main_tccl_id": next(iter(tccl_ids)),
        "datafixers_main_call_wall_ns": call_wall,
        "clinit_wall_ns": clinit_wall,
        "create_fixer_upper_body_ns": create_body,
        "clinit_residual_outside_create_body_ns": clinit_residual,
        "create_segments_ns": create_segments,
        "add_fixers": {
            "wall_ns": add_fixers_wall,
            "schema_calls": summary["schema_calls"],
            "schema_total_ns": summary["schema_total_ns"],
            "schema_first_ns": summary["schema_first_ns"],
            "schema_second_ns": summary["schema_second_ns"],
            "schema_rest_ns": summary["schema_rest_ns"],
            "fixer_register_calls": summary["fixer_register_calls"],
            "fixer_register_total_ns": summary["fixer_register_total_ns"],
            "fix_rule_construction_helpers_and_probe_residual_ns": add_fixers_residual,
        },
        "datafixerupper_class_init": {
            "jvm_log_line": init_line,
            "nested_in_second_schema_call": True,
            "inclusive_second_schema_upper_bound_ns": summary["schema_second_ns"],
            "interpretation": "DFU DataFixerUpper class initialization, including its static OPTIMIZATION_RULE construction, begins inside the second addSchema call. The second addSchema wall is only an inclusive upper bound, not a pure optimizer duration.",
        },
        "builder_build_call_ns": create_segments["builder_build_call"],
        "optimize_method_body_ns": optimize_body,
        "optimize_known_calls_ns": optimize_body_known_calls,
        "optimize_residual_outside_known_calls_ns": optimize_body_residual,
        "future_overlap_window_before_join_ns": overlap_window,
        "future_join_wait_ns": join_wall,
        "outer_segments_ns": outer_segments,
        "dag": [
            {"id": "datafixers_clinit", "kind": "serial_class_initialization", "wall_ns": clinit_wall, "predecessors": []},
            {"id": "create_prefix", "kind": "serial_version_lookup_and_builder_allocation", "wall_ns": create_segments["create_prefix_version_and_allocation"], "predecessors": ["datafixers_clinit"]},
            {"id": "builder_constructor", "kind": "serial_call", "wall_ns": create_segments["builder_constructor_call"], "predecessors": ["create_prefix"]},
            {"id": "add_fixers", "kind": "serial_schema_and_fix_registration", "wall_ns": add_fixers_wall, "predecessors": ["builder_constructor"]},
            {"id": "schema_calls", "kind": "nested_serial_schema_factory_calls", "wall_ns": summary["schema_total_ns"], "predecessors": ["add_fixers"]},
            {"id": "datafixerupper_clinit_optimizer", "kind": "nested_jvm_class_initialization_in_second_schema", "wall_ns": None, "inclusive_upper_bound_ns": summary["schema_second_ns"], "predecessors": ["schema_calls"]},
            {"id": "fix_rule_construction_residual", "kind": "nested_serial_residual_not_proven_pure", "wall_ns": add_fixers_residual, "predecessors": ["add_fixers"]},
            {"id": "builder_build", "kind": "serial_snapshot_and_result_build", "wall_ns": create_segments["builder_build_call"], "predecessors": ["add_fixers"]},
            {"id": "datafixer_async_work", "kind": "concurrent_uninstrumented_stock_future", "start": "after_result_optimize", "completion": "unknown_but_no_later_than_join_return", "predecessors": ["builder_build"]},
            {"id": "datafixer_join", "kind": "future_wait", "wall_ns": join_wall, "predecessors": ["datafixer_async_work"]},
        ],
        "classification_notes": {
            "schema_calls": "addSchema timing includes DataFixerBuilder parent lookup, schema factory execution and schema registration. It does not imply schema construction is pure or movable.",
            "fix_rule_residual": "addFixer itself only registers already-created DataFix objects. Therefore enclosing addFixers wall minus schema/addFixer calls contains fix/rule object construction, helper/static-factory/lambda work, ordinary bytecode and probe overhead; it is deliberately not labelled pure rule construction.",
            "optimizer": "JVM -Xlog:class+init proves DataFixerUpper initialization starts inside the second addSchema call. DFU's static OPTIMIZATION_RULE is part of that class initialization, but this probe does not split its duration from the rest of the second schema call and does not force class initialization.",
            "build": "DataFixerBuilder.build remains a separate stock call after addFixers; no Result/DataFixer cache, publication change or executor change is introduced.",
            "purity_gate": "No prepare->barrier->commit frontier is established by timing alone. Any future proposal must separately prove that selected construction is independent of JVM class initialization, static publication, schema parent ordering and failure order.",
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
    print("BOOTOPTIM_AGENT102_DATAFIXERS_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
