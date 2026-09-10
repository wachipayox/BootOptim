#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

PREFIX = "BOOTOPTIM_MAIN_LIFECYCLE "


def kv(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", line))


def parse(text: str) -> dict:
    events: list[dict] = []
    for line_no, line in enumerate(text.splitlines(), 1):
        if PREFIX not in line:
            continue
        item = kv(line)
        if "event" not in item or "mono_ns" not in item:
            continue
        item["line"] = line_no
        item["mono_ns"] = int(item["mono_ns"])
        events.append(item)

    def matches(name: str) -> list[dict]:
        return [e for e in events if e.get("event") == name]

    def one(name: str) -> dict:
        found = matches(name)
        if len(found) != 1:
            raise ValueError(f"expected exactly one {name}, found {len(found)}")
        return found[0]

    outer_before = one("before_shared_constants_version")
    outer_after = one("after_shared_constants_version")
    if outer_after["mono_ns"] < outer_before["mono_ns"]:
        raise ValueError("outer SharedConstants call has negative wall")

    names = [
        "shared_constants_clinit_enter",
        "before_netty_leak_level_resolve",
        "after_netty_leak_level_publish",
        "before_duration_constant",
        "after_duration_constant",
        "before_resource_leak_detector_set_level",
        "after_resource_leak_detector_set_level",
        "before_command_syntax_stack_trace_publish",
        "after_command_syntax_stack_trace_publish",
        "before_brigadier_exceptions_construction",
        "after_brigadier_exceptions_construction",
        "after_brigadier_provider_publish",
        "shared_constants_clinit_exit",
    ]
    start_outer = outer_before["mono_ns"]
    end_outer = outer_after["mono_ns"]
    by_name: dict[str, dict] = {}
    for name in names:
        found = [e for e in matches(name) if start_outer <= e["mono_ns"] <= end_outer]
        if len(found) != 1:
            raise ValueError(f"expected exactly one {name} inside Main SharedConstants interval, found {len(found)}")
        by_name[name] = found[0]

    stamps = [by_name[name]["mono_ns"] for name in names]
    if stamps != sorted(stamps):
        raise ValueError(f"SharedConstants clinit marker order changed: {dict(zip(names, stamps))}")

    thread = by_name[names[0]].get("thread")
    tccl_id = by_name[names[0]].get("tccl_id")
    for name in names:
        event = by_name[name]
        if event.get("thread") != thread or event.get("tccl_id") != tccl_id:
            raise ValueError(f"{name} changed thread/TCCL inside SharedConstants.<clinit>")

    t = {name: by_name[name]["mono_ns"] for name in names}
    segments = {
        "clinit_entry_to_netty_level_active_use": t["before_netty_leak_level_resolve"] - t["shared_constants_clinit_enter"],
        "netty_level_resolve_and_shared_field_publish": t["after_netty_leak_level_publish"] - t["before_netty_leak_level_resolve"],
        "netty_level_to_duration_active_use": t["before_duration_constant"] - t["after_netty_leak_level_publish"],
        "duration_of_millis_to_max_tick_publish": t["after_duration_constant"] - t["before_duration_constant"],
        "post_duration_constants_array_to_resource_leak_call": t["before_resource_leak_detector_set_level"] - t["after_duration_constant"],
        "resource_leak_detector_set_level_active_use": t["after_resource_leak_detector_set_level"] - t["before_resource_leak_detector_set_level"],
        "post_netty_to_command_syntax_active_use": t["before_command_syntax_stack_trace_publish"] - t["after_resource_leak_detector_set_level"],
        "command_syntax_stack_trace_field_publish_active_use": t["after_command_syntax_stack_trace_publish"] - t["before_command_syntax_stack_trace_publish"],
        "post_command_syntax_to_brigadier_active_use": t["before_brigadier_exceptions_construction"] - t["after_command_syntax_stack_trace_publish"],
        "brigadier_exceptions_new_and_constructor_active_use": t["after_brigadier_exceptions_construction"] - t["before_brigadier_exceptions_construction"],
        "brigadier_provider_field_publish": t["after_brigadier_provider_publish"] - t["after_brigadier_exceptions_construction"],
        "provider_publish_to_clinit_exit": t["shared_constants_clinit_exit"] - t["after_brigadier_provider_publish"],
    }
    wall = t["shared_constants_clinit_exit"] - t["shared_constants_clinit_enter"]
    if any(value < 0 for value in segments.values()):
        raise ValueError(f"negative SharedConstants clinit segment: {segments}")
    if sum(segments.values()) != wall:
        raise ValueError(f"SharedConstants clinit segments do not tile wall: {sum(segments.values())} != {wall}")

    ranked = sorted(segments.items(), key=lambda item: item[1], reverse=True)
    return {
        "schema": 1,
        "probe": "agent103-sharedconstants-clinit-v1",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "metric_type": "single-run monotonic causal wall; observational coarse boundaries; not A/B and not a savings estimate",
        "shared_constants_clinit_wall_ns": wall,
        "segments_ns": segments,
        "ranked_segments_ns": ranked,
        "residual_ns": wall - sum(segments.values()),
        "thread": thread,
        "tccl_id": tccl_id,
        "classification_notes": {
            "active_use_semantics": "GETSTATIC/INVOKESTATIC/PUTSTATIC/NEW boundaries can include JVM linkage and first class initialization triggered by that exact stock active use. The probe never initializes those classes itself.",
            "netty_level": "Includes first active use of ResourceLeakDetector.Level.DISABLED and the unchanged SharedConstants.NETTY_LEAK_DETECTION PUTSTATIC.",
            "resource_leak_detector": "Includes the unchanged ResourceLeakDetector.setLevel invocation; if ResourceLeakDetector is not already initialized, JVM class initialization is charged here before method execution. No Netty property or level is read or changed by the probe.",
            "command_syntax": "Includes the unchanged first PUTSTATIC of CommandSyntaxException.ENABLE_COMMAND_STACK_TRACES; first initialization of that Brigadier class, if pending, is charged here.",
            "brigadier_exceptions": "Includes NEW plus the stock BrigadierExceptions constructor; first initialization of BrigadierExceptions and its transitive static exception/component setup, if pending, is charged here.",
            "future_boundary": "A material segment is not optimization permission. Any future prepare/barrier/commit split requires independent proof that it is pure and order-independent under JVM class-initialization semantics and preserves properties, global providers, failure order and publication.",
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = parse(args.console.read_text(encoding="utf-8", errors="replace"))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_AGENT103_SHARED_CONSTANTS_CLINIT_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
