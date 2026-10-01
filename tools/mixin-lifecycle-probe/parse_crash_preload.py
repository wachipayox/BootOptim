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

REQUIRED_CRASH_EVENTS = (
    "preload_entry",
    "before_memory_reserve",
    "after_memory_reserve",
    "before_dummy_construction",
    "after_dummy_construction",
    "before_friendly_report",
    "friendly_core_entry",
    "before_exception_message",
    "after_exception_message",
    "before_details",
    "after_details",
    "friendly_core_exit",
    "after_friendly_report",
    "preload_exit",
)


def kv(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", line))


def parse(text: str) -> dict:
    requests: list[dict] = []
    main_events: list[dict] = []
    crash_events: list[dict] = []

    for line_no, line in enumerate(text.splitlines(), 1):
        if "BOOTOPTIM_ML_FORK_REQUEST " in line:
            item = kv(line)
            if item.get("class") == TARGET:
                item["line"] = line_no
                item["request_id"] = int(item["request_id"])
                requests.append(item)
        elif "BOOTOPTIM_MAIN_LIFECYCLE " in line:
            item = kv(line)
            item["line"] = line_no
            item["mono_ns"] = int(item["mono_ns"])
            main_events.append(item)
        elif "BOOTOPTIM_CRASH_PRELOAD " in line:
            item = kv(line)
            item["line"] = line_no
            item["mono_ns"] = int(item["mono_ns"])
            item["thread_id"] = int(item["thread_id"])
            crash_events.append(item)

    if len(requests) != 2 or [r["request_id"] for r in requests] != [1, 2]:
        raise ValueError(f"expected Bootstrap requests [1,2] in order, found {[r['request_id'] for r in requests]}")
    for index, (request, expected) in enumerate(zip(requests, EXPECTED_REQUESTS), 1):
        for key, value in expected.items():
            if request.get(key) != value:
                raise ValueError(f"request {index} {key}={request.get(key)!r}, expected {value!r}")
    if requests[0].get("loader_id") != requests[1].get("loader_id") or requests[0].get("tccl_id") != requests[1].get("tccl_id"):
        raise ValueError("Bootstrap requests no longer share TransformingClassLoader/TCCL")
    if requests[0]["line"] >= requests[1]["line"]:
        raise ValueError("Bootstrap request order changed")

    outer: dict[str, dict] = {}
    for name in ("before_crash_report_preload", "after_crash_report_preload"):
        matches = [event for event in main_events if event.get("event") == name]
        if len(matches) != 1:
            raise ValueError(f"expected exactly one outer marker {name}, found {len(matches)}")
        outer[name] = matches[0]

    crash: dict[str, dict] = {}
    for name in REQUIRED_CRASH_EVENTS:
        matches = [event for event in crash_events if event.get("event") == name]
        if len(matches) != 1:
            raise ValueError(f"expected exactly one CrashReport marker {name}, found {len(matches)}")
        crash[name] = matches[0]

    sequence = [
        outer["before_crash_report_preload"],
        *[crash[name] for name in REQUIRED_CRASH_EVENTS],
        outer["after_crash_report_preload"],
    ]
    mono = [event["mono_ns"] for event in sequence]
    if mono != sorted(mono):
        raise ValueError(f"CrashReport marker ordering changed: {mono}")
    line_order = [event["line"] for event in sequence]
    if line_order != sorted(line_order):
        raise ValueError("CrashReport log ordering changed")

    threads = {event.get("thread") for event in sequence}
    if len(threads) != 1:
        raise ValueError(f"CrashReport preload crossed threads unexpectedly: {sorted(threads)}")
    crash_thread_ids = {event["thread_id"] for event in crash.values()}
    if len(crash_thread_ids) != 1:
        raise ValueError(f"CrashReport internal markers crossed thread ids: {sorted(crash_thread_ids)}")

    before_outer = outer["before_crash_report_preload"]["mono_ns"]
    after_outer = outer["after_crash_report_preload"]["mono_ns"]
    t = {name: crash[name]["mono_ns"] for name in REQUIRED_CRASH_EVENTS}

    preload_partition = {
        "outer_call_to_preload_entry_class_init_or_dispatch": t["preload_entry"] - before_outer,
        "preload_entry_residual": t["before_memory_reserve"] - t["preload_entry"],
        "memory_reserve_allocate": t["after_memory_reserve"] - t["before_memory_reserve"],
        "post_reserve_residual": t["before_dummy_construction"] - t["after_memory_reserve"],
        "dummy_report_construction_including_throwable_and_system_report": t["after_dummy_construction"] - t["before_dummy_construction"],
        "post_construction_residual": t["before_friendly_report"] - t["after_dummy_construction"],
        "friendly_report_total": t["after_friendly_report"] - t["before_friendly_report"],
        "post_render_to_preload_exit": t["preload_exit"] - t["after_friendly_report"],
        "preload_exit_to_outer_return": after_outer - t["preload_exit"],
    }
    outer_wall = after_outer - before_outer
    if sum(preload_partition.values()) != outer_wall:
        raise ValueError("CrashReport outer partition does not tile the Agent 98 callsite wall")

    friendly_partition = {
        "overload_delegation_entry": t["friendly_core_entry"] - t["before_friendly_report"],
        "friendly_header_time_description_residual": t["before_exception_message"] - t["friendly_core_entry"],
        "exception_message_stacktrace_formatting": t["after_exception_message"] - t["before_exception_message"],
        "post_exception_rendering_residual": t["before_details"] - t["after_exception_message"],
        "details_and_system_report_rendering": t["after_details"] - t["before_details"],
        "friendly_core_return_residual": t["friendly_core_exit"] - t["after_details"],
        "overload_delegation_return": t["after_friendly_report"] - t["friendly_core_exit"],
    }
    friendly_wall = t["after_friendly_report"] - t["before_friendly_report"]
    if sum(friendly_partition.values()) != friendly_wall:
        raise ValueError("friendly-report partition does not tile its enclosing call")

    dag = [
        {"id": "agent98_before_crash_preload", "kind": "serial_main_thread_boundary", "predecessors": []},
        {"id": "crash_class_init_or_entry_dispatch", "kind": "serial_residual", "wall_ns": preload_partition["outer_call_to_preload_entry_class_init_or_dispatch"], "predecessors": ["agent98_before_crash_preload"]},
        {"id": "memory_reserve_allocate", "kind": "serial_stock_call", "wall_ns": preload_partition["memory_reserve_allocate"], "predecessors": ["crash_class_init_or_entry_dispatch"]},
        {"id": "dummy_report_construction", "kind": "serial_stock_construction", "wall_ns": preload_partition["dummy_report_construction_including_throwable_and_system_report"], "predecessors": ["memory_reserve_allocate"]},
        {"id": "friendly_report_render", "kind": "serial_stock_render", "wall_ns": preload_partition["friendly_report_total"], "predecessors": ["dummy_report_construction"]},
        {"id": "exception_stacktrace_formatting", "kind": "nested_render_subphase", "wall_ns": friendly_partition["exception_message_stacktrace_formatting"], "predecessors": ["friendly_report_render"]},
        {"id": "details_system_report_rendering", "kind": "nested_render_subphase", "wall_ns": friendly_partition["details_and_system_report_rendering"], "predecessors": ["friendly_report_render"]},
        {"id": "agent98_after_crash_preload", "kind": "serial_main_thread_boundary", "predecessors": ["friendly_report_render"]},
    ]

    return {
        "schema": 1,
        "probe": "agent100-crashreport-preload-v1",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "metric_type": "single-run monotonic observational wall; nested render phases are inclusive within friendly_report_total; not A/B and not a savings estimate",
        "request_contract": "exactly_two_bootstrap_requests_mixin_then_classloading",
        "request_count": len(requests),
        "same_loader_id": requests[0].get("loader_id"),
        "thread": sequence[0].get("thread"),
        "outer_crash_report_call_wall_ns": outer_wall,
        "preload_partition_ns": preload_partition,
        "friendly_report_partition_ns": friendly_partition,
        "dag": dag,
        "classification_notes": {
            "outer_call_to_preload_entry_class_init_or_dispatch": "This is the only bucket that can contain first-use CrashReport class initialization before preload() begins; it must not be attributed to MemoryReserve or dummy rendering by subtraction.",
            "dummy_report_construction_including_throwable_and_system_report": "Measured from before NEW CrashReport until the constructor returns. It intentionally includes nested Throwable construction/stack capture and CrashReport field initialization such as SystemReport; no marker is inserted while an uninitialized object is on the operand stack.",
            "exception_message_stacktrace_formatting": "Exact getExceptionMessage() call inside the core friendly-report renderer; this prints/formats the dummy Throwable stack trace into the report text.",
            "details_and_system_report_rendering": "Exact getDetails(StringBuilder) call, including crash details/system report extension and rendering performed by stock/NeoForge code.",
        },
        "marker_counts": {"main": len(main_events), "crash_preload": len(crash_events)},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = parse(args.console.read_text(encoding="utf-8", errors="replace"))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_AGENT100_CRASH_PRELOAD_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
