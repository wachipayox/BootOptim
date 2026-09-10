#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TARGET = "net.minecraft.server.Bootstrap"
PROBE = "agent96-mixin-prepareconfigs-suffix-v2"
EXPECTED = (
    ("securejar_get_maybe_transformed_bytes", "mixin", "mixin", "org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode"),
    ("securejar_reader_to_class", "null", "classloading", "net.minecraft.client.main.Main#lambda$main$0"),
)


def fields(line: str, marker: str) -> dict[str, str]:
    tail = line.split(marker, 1)[1]
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", tail))


def parse(text: str) -> dict:
    requests: list[dict] = []
    ends: dict[int, dict] = {}
    begins: dict[int, dict] = {}
    mixin: list[dict] = []
    main: dict[str, dict] = {}

    for line_no, line in enumerate(text.splitlines(), 1):
        if "BOOTOPTIM_ML_FORK_REQUEST_END " in line:
            item = fields(line, "BOOTOPTIM_ML_FORK_REQUEST_END ")
            if item.get("class") == TARGET:
                rid = int(item["request_id"])
                ends[rid] = {**item, "line": line_no, "mono_ns": int(item["mono_ns"])}
        elif "BOOTOPTIM_ML_FORK_REQUEST " in line:
            item = fields(line, "BOOTOPTIM_ML_FORK_REQUEST ")
            if item.get("class") == TARGET:
                requests.append({**item, "line": line_no, "request_id": int(item["request_id"])})
        elif "BOOTOPTIM_ML_FORK " in line:
            item = fields(line, "BOOTOPTIM_ML_FORK ")
            if item.get("class") == TARGET and item.get("stage") == "class_transform_begin":
                begins[int(item["request_id"])] = {**item, "line": line_no, "mono_ns": int(item["mono_ns"])}
        elif "BOOTOPTIM_MIXIN_LIFECYCLE " in line:
            item = fields(line, "BOOTOPTIM_MIXIN_LIFECYCLE ")
            if item.get("probe") == PROBE:
                mixin.append({**item, "line": line_no, "mono_ns": int(item["mono_ns"]), "elapsed_ns": int(item.get("elapsed_ns", "0"))})
        elif "BOOTOPTIM_MAIN_LIFECYCLE " in line:
            item = fields(line, "BOOTOPTIM_MAIN_LIFECYCLE ")
            main[item["event"]] = {**item, "line": line_no, "mono_ns": int(item["mono_ns"])}

    if [r["request_id"] for r in requests] != [1, 2]:
        raise ValueError(f"expected exactly Bootstrap requests [1,2], got {[r['request_id'] for r in requests]}")
    for i, (origin, raw, reason, caller) in enumerate(EXPECTED, 1):
        r = requests[i - 1]
        expected = {"origin": origin, "raw_context": raw, "effective_reason": reason, "caller": caller}
        for key, value in expected.items():
            if r.get(key) != value:
                raise ValueError(f"request {i} {key}={r.get(key)!r}, expected {value!r}")
        if i not in ends or i not in begins or ends[i].get("observed_id") != str(i):
            raise ValueError(f"request {i} missing causal markers")
    if requests[0].get("loader_id") != requests[1].get("loader_id") or requests[0].get("tccl_id") != requests[1].get("tccl_id"):
        raise ValueError("Bootstrap requests do not share loader/TCCL")
    if not (requests[0]["line"] < ends[1]["line"] < requests[1]["line"]):
        raise ValueError("request topology is not completed Mixin request -> classloading request")

    by_event = {e["event"]: e for e in mixin}
    names = [
        "check_select_enter", "select_enter", "prepare_configs_enter",
        "listener_registration_enter", "listener_registration_exit",
        "config_prepare_enter", "config_prepare_exit",
        "plugin_accept_targets_enter", "plugin_accept_targets_exit",
        "post_initialise_enter", "post_initialise_exit",
        "config_commit_enter", "config_commit_exit",
        "prepare_configs_exit", "select_exit", "check_select_exit",
    ]
    missing = [name for name in names if name not in by_event]
    if missing:
        raise ValueError(f"missing Mixin lifecycle markers: {missing}")
    if [by_event[n]["line"] for n in names] != sorted(by_event[n]["line"] for n in names):
        raise ValueError("Mixin lifecycle ordering changed")
    required_main = ["main_before_run_and_tick", "bootstrap_worker_entry", "bootstrap_worker_return", "main_after_run_and_tick"]
    if any(name not in main for name in required_main):
        raise ValueError("missing Main lifecycle markers")

    t = lambda name: by_event[name]["mono_ns"]
    r1_end = ends[1]["mono_ns"]
    r2_begin = begins[2]["mono_ns"]
    prepare_suffix = t("prepare_configs_exit") - r1_end
    suffix = {
        "config_prepare_remaining_after_request1": t("config_prepare_exit") - r1_end,
        "boundary_residual_prepare_to_plugin": t("plugin_accept_targets_enter") - t("config_prepare_exit"),
        "plugin_accept_targets_callbacks": t("plugin_accept_targets_exit") - t("plugin_accept_targets_enter"),
        "boundary_residual_plugin_to_post_initialise": t("post_initialise_enter") - t("plugin_accept_targets_exit"),
        "post_initialise_mutable_validation_callbacks": t("post_initialise_exit") - t("post_initialise_enter"),
        "boundary_residual_post_initialise_to_commit": t("config_commit_enter") - t("post_initialise_exit"),
        "config_commit_add_sort_clear": t("config_commit_exit") - t("config_commit_enter"),
        "boundary_residual_commit_to_prepare_configs_exit": t("prepare_configs_exit") - t("config_commit_exit"),
    }
    if any(v < 0 for v in suffix.values()) or sum(suffix.values()) != prepare_suffix:
        raise ValueError("prepareConfigs suffix partition does not tile exactly")

    def scope(a: str, b: str, inclusive: bool = False) -> dict:
        return {"start_ns": t(a), "end_ns": t(b), "wall_ns": t(b) - t(a), "inclusive": inclusive, "thread": by_event[a].get("thread")}

    coarse = {
        "listener_registration": scope("listener_registration_enter", "listener_registration_exit"),
        "config_prepare": scope("config_prepare_enter", "config_prepare_exit"),
        "plugin_accept_targets": scope("plugin_accept_targets_enter", "plugin_accept_targets_exit"),
        "post_initialise": scope("post_initialise_enter", "post_initialise_exit"),
        "config_commit": scope("config_commit_enter", "config_commit_exit"),
    }
    outer = {
        "checkSelect": scope("check_select_enter", "check_select_exit", True),
        "select": scope("select_enter", "select_exit", True),
        "prepareConfigs": scope("prepare_configs_enter", "prepare_configs_exit", True),
    }
    segments = {
        "request1_end_to_prepare_configs_exit": prepare_suffix,
        "prepare_configs_exit_to_select_exit": t("select_exit") - t("prepare_configs_exit"),
        "select_exit_to_check_select_exit": t("check_select_exit") - t("select_exit"),
        "check_select_exit_to_background_waiter_call": main["main_before_run_and_tick"]["mono_ns"] - t("check_select_exit"),
        "background_waiter_call_to_worker_entry": main["bootstrap_worker_entry"]["mono_ns"] - main["main_before_run_and_tick"]["mono_ns"],
        "worker_entry_to_request2_transform_begin": r2_begin - main["bootstrap_worker_entry"]["mono_ns"],
        "request1_end_to_request2_transform_begin": r2_begin - r1_end,
    }
    if any(v < 0 for v in segments.values()):
        raise ValueError("negative causal segment")

    return {
        "schema": 5,
        "metric_type": "single-run monotonic causal wall; checkSelect/select/prepareConfigs are inclusive; coarse prepareConfigs method-block scopes are non-overlapping; not A/B or savings",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu",
        "request_contract": "mixin_transformed_bytes_then_classloading",
        "same_loader_id": requests[0].get("loader_id"),
        "mixin_scopes": outer,
        "prepare_configs_coarse_scopes": coarse,
        "prepare_configs_suffix_partition_ns": suffix,
        "segments_ns": segments,
        "dag": [
            {"id": "request1_mixin_bytes", "predecessors": []},
            {"id": "config_prepare_remaining", "wall_ns": suffix["config_prepare_remaining_after_request1"], "kind": "serial_mutable_prepare", "predecessors": ["request1_mixin_bytes"]},
            {"id": "plugin_accept_targets", "wall_ns": suffix["plugin_accept_targets_callbacks"], "kind": "ordered_callback_state", "predecessors": ["config_prepare_remaining"]},
            {"id": "post_initialise", "wall_ns": suffix["post_initialise_mutable_validation_callbacks"], "kind": "ordered_mutable_validation_callbacks", "predecessors": ["plugin_accept_targets"]},
            {"id": "config_commit", "wall_ns": suffix["config_commit_add_sort_clear"], "kind": "ordered_state_commit", "predecessors": ["post_initialise"]},
            {"id": "boundary_residual", "wall_ns": sum(v for k, v in suffix.items() if k.startswith("boundary_residual_")), "kind": "explicit_probe_boundary_residual", "predecessors": ["config_commit"]},
        ],
        "classification_notes": {
            "config_prepare": "Mutable: constructs MixinInfo, increments global mixin order, loads/transforms mixin bytes, invokes plugin filtering, mutates ClassInfo/target/config/listener-visible state. Whole scope is not a pure-work candidate.",
            "plugin_accept_targets": "Third-party IMixinConfigPlugin callback in stock pending-config order; do not move or parallelise.",
            "post_initialise": "May call plugin.getMixins, prepare companion mixins, validate MixinInfo, notify onInit listeners, and remove invalid mappings; ordered mutable state.",
            "config_commit": "Stock publication addAll/sort/clear; ordered state commit.",
            "future_purity": "Only a narrower immutable preparse could be investigated later, and only after proving independence from class acquisition/transformation, plugin callbacks, global mixin order, ClassInfo caches/target registration, listeners, extensions, validation and publication. Not implemented here.",
        },
        "marker_counts": {"mixin": len(mixin), "main": len(main)},
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--console", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    a = p.parse_args()
    result = parse(a.console.read_text(encoding="utf-8", errors="replace"))
    a.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_MIXIN_PREPARECONFIGS_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
