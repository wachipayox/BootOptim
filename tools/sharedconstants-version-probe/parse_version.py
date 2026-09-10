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
    outer_start = outer_before["mono_ns"]
    outer_end = outer_after["mono_ns"]
    if outer_end < outer_start:
        raise ValueError("outer SharedConstants call has negative wall")

    # SharedConstants.tryDetectVersion is legitimately called again later in startup after
    # CURRENT_VERSION has already been published. The Agent 101 target is specifically the callsite
    # in Main.main bounded by the pre-existing before/after markers, so fail closed on exactly one
    # occurrence of every nested boundary inside that outer interval rather than globally.
    def one_within(name: str) -> dict:
        found = [e for e in matches(name) if outer_start <= e["mono_ns"] <= outer_end]
        if len(found) != 1:
            raise ValueError(
                f"expected exactly one {name} inside Main SharedConstants interval, found {len(found)}"
            )
        return found[0]

    required_inner = [
        "shared_constants_try_detect_entry",
        "before_detected_version_call",
        "detected_version_try_detect_entry",
        "before_version_resource_open",
        "after_version_resource_open",
        "before_json_version_expression",
        "gson_version_parse_enter",
        "gson_version_parse_exit",
        "after_json_version_construction",
        "detected_version_try_detect_exit",
        "before_version_publication",
        "after_version_publication",
        "shared_constants_try_detect_exit",
    ]
    by_name = {
        "before_shared_constants_version": outer_before,
        "after_shared_constants_version": outer_after,
        **{name: one_within(name) for name in required_inner},
    }

    clinit_pairs = {}
    for key, enter_name, exit_name in (
        ("SharedConstants", "shared_constants_clinit_enter", "shared_constants_clinit_exit"),
        ("DetectedVersion", "detected_version_clinit_enter", "detected_version_clinit_exit"),
        ("GsonHelper", "gson_helper_clinit_enter", "gson_helper_clinit_exit"),
    ):
        enter = one_within(enter_name)
        exit_event = one_within(exit_name)
        if exit_event["mono_ns"] < enter["mono_ns"]:
            raise ValueError(f"{key} clinit exit precedes entry")
        clinit_pairs[key] = (enter, exit_event)

    ordered_names = [
        "before_shared_constants_version",
        "shared_constants_try_detect_entry",
        "before_detected_version_call",
        "detected_version_try_detect_entry",
        "before_version_resource_open",
        "after_version_resource_open",
        "before_json_version_expression",
        "gson_version_parse_enter",
        "gson_version_parse_exit",
        "after_json_version_construction",
        "detected_version_try_detect_exit",
        "before_version_publication",
        "after_version_publication",
        "shared_constants_try_detect_exit",
        "after_shared_constants_version",
    ]
    stamps = [by_name[name]["mono_ns"] for name in ordered_names]
    if stamps != sorted(stamps):
        raise ValueError(f"version detection marker order changed: {dict(zip(ordered_names, stamps))}")

    thread = outer_before.get("thread")
    tccl_id = outer_before.get("tccl_id")
    for name in ordered_names:
        event = by_name[name]
        if event.get("thread") != thread or event.get("tccl_id") != tccl_id:
            raise ValueError(f"{name} changed thread/TCCL within version detection")
    for key, pair in clinit_pairs.items():
        for event in pair:
            if event.get("thread") != thread or event.get("tccl_id") != tccl_id:
                raise ValueError(f"{key} clinit inside outer call changed thread/TCCL")

    values = {name: by_name[name]["mono_ns"] for name in ordered_names}
    segments = {
        "outer_call_to_shared_method_entry": values["shared_constants_try_detect_entry"] - values["before_shared_constants_version"],
        "shared_method_prefix_to_detected_call": values["before_detected_version_call"] - values["shared_constants_try_detect_entry"],
        "detected_call_to_method_entry": values["detected_version_try_detect_entry"] - values["before_detected_version_call"],
        "detected_method_prefix_to_resource_open": values["before_version_resource_open"] - values["detected_version_try_detect_entry"],
        "version_resource_lookup_open": values["after_version_resource_open"] - values["before_version_resource_open"],
        "resource_open_to_json_expression": values["before_json_version_expression"] - values["after_version_resource_open"],
        "json_expression_to_gson_parse_entry": values["gson_version_parse_enter"] - values["before_json_version_expression"],
        "gson_parse_reader_including_stream_reads": values["gson_version_parse_exit"] - values["gson_version_parse_enter"],
        "gson_parse_exit_to_version_object_constructed": values["after_json_version_construction"] - values["gson_version_parse_exit"],
        "version_object_to_detected_return": values["detected_version_try_detect_exit"] - values["after_json_version_construction"],
        "detected_return_to_publication": values["before_version_publication"] - values["detected_version_try_detect_exit"],
        "current_version_publication": values["after_version_publication"] - values["before_version_publication"],
        "shared_method_tail": values["shared_constants_try_detect_exit"] - values["after_version_publication"],
        "shared_return_to_outer_callsite": values["after_shared_constants_version"] - values["shared_constants_try_detect_exit"],
    }
    outer_wall = outer_end - outer_start
    if sum(segments.values()) != outer_wall:
        raise ValueError(f"version segments do not tile outer call: {sum(segments.values())} != {outer_wall}")

    class_initialization = {}
    for key, (enter, exit_event) in clinit_pairs.items():
        start = enter["mono_ns"]
        end = exit_event["mono_ns"]
        class_initialization[key] = {
            "start_ns": start,
            "end_ns": end,
            "wall_ns": end - start,
            "within_outer_call": True,
            "before_outer_call": False,
            "thread": enter.get("thread"),
        }

    observed_calls = {
        "shared_constants_try_detect_total": len(matches("shared_constants_try_detect_entry")),
        "shared_constants_try_detect_inside_main_call": len(
            [e for e in matches("shared_constants_try_detect_entry") if outer_start <= e["mono_ns"] <= outer_end]
        ),
        "detected_version_try_detect_total": len(matches("detected_version_try_detect_entry")),
        "detected_version_try_detect_inside_main_call": len(
            [e for e in matches("detected_version_try_detect_entry") if outer_start <= e["mono_ns"] <= outer_end]
        ),
    }

    return {
        "schema": 2,
        "probe": "agent101-sharedconstants-version-detection-v1",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "metric_type": "single-run monotonic causal wall; observational coarse boundaries; not A/B and not a savings estimate",
        "outer_shared_constants_try_detect_version_wall_ns": outer_wall,
        "segments_ns": segments,
        "class_initialization": class_initialization,
        "observed_call_counts": observed_calls,
        "thread": thread,
        "tccl_id": tccl_id,
        "classification_notes": {
            "main_call_scope": "Nested markers are selected only inside the existing Main.main before/after SharedConstants.tryDetectVersion boundary. Later legitimate calls are counted but excluded from this attribution.",
            "version_resource_lookup_open": "Wall across Class.getResourceAsStream('/version.json'): resource lookup plus opening the JAR-backed stream; it does not include later stream consumption.",
            "gson_parse_reader_including_stream_reads": "Wall inside GsonHelper.parse(Reader). Because Gson consumes the Reader here, this includes JSON parsing plus the InputStreamReader/JAR stream reads caused by parsing; the diagnostic intentionally does not pretend to separate those effects.",
            "json_expression_to_gson_parse_entry": "Residual from NEW DetectedVersion until GsonHelper.parse(Reader) entry; if GsonHelper first initializes here, its <clinit> is nested in this interval.",
            "gson_parse_exit_to_version_object_constructed": "Residual after parse return through the stock DetectedVersion(JsonObject) constructor. No constructor substitution or field extraction is performed.",
            "current_version_publication": "Wall across the original PUTSTATIC SharedConstants.CURRENT_VERSION only; publication semantics and failure behavior remain stock.",
            "class_initialization": "<clinit> walls are reported independently inside the outer Main call. Linkage before the first <clinit> instruction remains in the surrounding dispatch residual.",
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = parse(args.console.read_text(encoding="utf-8", errors="replace"))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_AGENT101_VERSION_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
