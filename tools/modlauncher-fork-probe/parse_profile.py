#!/usr/bin/env python3
"""Parse Agent 94 target-only ModLauncher fork trace without relaxing exact-pack validity gates."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TARGET = "net.minecraft.server.Bootstrap"
STRICT_LABEL = "boot_optim_agent94_bootstrap_profile"


def parse_kv(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z_]+)=([^\s]+)", line))


def _int(data: dict[str, str], key: str) -> int | None:
    value = data.get(key)
    return int(value) if value is not None else None


def _callback(data: dict[str, str]) -> dict:
    result = {
        "stage": data.get("stage"),
        "thread": data.get("thread"),
    }
    for key in ("start_ns", "end_ns", "elapsed_ns", "mono_ns"):
        value = _int(data, key)
        if value is not None:
            result[key] = value
    if data.get("owner") is not None:
        result["owner"] = data["owner"]
    if data.get("labels") is not None:
        result["labels"] = data["labels"]
    return result


def parse_profile(console_text: str) -> dict:
    identities: list[dict[str, str]] = []
    accept_ns: int | None = None
    entry_ns: int | None = None
    open_by_thread: dict[str, dict] = {}
    invocations: list[dict] = []

    for line_number, line in enumerate(console_text.splitlines(), start=1):
        if "BOOTOPTIM_ML_FORK_IDENTITY" in line:
            identities.append(parse_kv(line))
            continue
        if "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY" in line:
            data = parse_kv(line)
            if data.get("event") == "transform_accept":
                if accept_ns is not None:
                    raise ValueError("multiple transform_accept boundaries")
                accept_ns = int(data["mono_ns"])
            elif data.get("event") == "bootstrap_entry":
                if entry_ns is not None:
                    raise ValueError("multiple bootstrap_entry boundaries")
                entry_ns = int(data["mono_ns"])
            continue
        if "BOOTOPTIM_ML_FORK " not in line:
            continue

        data = parse_kv(line)
        if data.get("class") != TARGET:
            continue
        labels_match = re.search(r"labels=(\[.*?\])(?:\s|$)", line)
        if labels_match:
            data["labels"] = labels_match.group(1)
        stage = data.get("stage")
        thread = data.get("thread", "<unknown>")

        if stage == "class_transform_begin":
            if thread in open_by_thread:
                raise ValueError(f"nested target transform on thread {thread}")
            invocation = {
                "thread": thread,
                "begin_ns": int(data["mono_ns"]),
                "begin_line": line_number,
                "callbacks": [],
                "transformers": [],
            }
            open_by_thread[thread] = invocation
            invocations.append(invocation)
            continue

        invocation = open_by_thread.get(thread)
        if invocation is None:
            raise ValueError(f"target stage {stage!r} without open invocation on thread {thread}")
        if stage == "class_transform_return":
            invocation["return_ns"] = int(data["mono_ns"])
            invocation["return_line"] = line_number
            open_by_thread.pop(thread)
            continue

        item = _callback(data)
        if stage == "transformer":
            invocation["transformers"].append(item)
        else:
            invocation["callbacks"].append(item)

    if open_by_thread:
        raise ValueError(f"unterminated target transforms: {sorted(open_by_thread)}")
    if accept_ns is None or entry_ns is None:
        raise ValueError("missing strict transform_accept/bootstrap_entry boundary")
    if entry_ns <= accept_ns:
        raise ValueError("non-positive accept_to_entry interval")
    if len(identities) != 1 or identities[0].get("module") != "cpw.mods.modlauncher":
        raise ValueError(f"runtime identity invalid: {identities}")
    if not invocations:
        raise ValueError("no target ClassTransformer invocations")

    invocations.sort(key=lambda item: item["begin_ns"])
    accept_index: int | None = None
    for index, invocation in enumerate(invocations):
        if "return_ns" not in invocation:
            raise ValueError(f"target invocation {index} missing return")
        if invocation["return_ns"] < invocation["begin_ns"]:
            raise ValueError(f"target invocation {index} has negative duration")
        invocation["index"] = index
        invocation["elapsed_ns"] = invocation["return_ns"] - invocation["begin_ns"]
        invocation["contains_accept"] = invocation["begin_ns"] <= accept_ns <= invocation["return_ns"]
        invocation["strict_transformers"] = [
            item for item in invocation["transformers"] if STRICT_LABEL in item.get("labels", "")
        ]
        if invocation["contains_accept"]:
            if accept_index is not None:
                raise ValueError("transform_accept falls inside multiple target invocations")
            accept_index = index
        stages = {item["stage"]: item for item in invocation["callbacks"] if item.get("stage")}
        invocation["stage_elapsed_ns"] = {
            name: item["elapsed_ns"] for name, item in stages.items() if "elapsed_ns" in item
        }
        invocation["transformer_callback_wall_sum_ns"] = sum(
            item.get("elapsed_ns", 0) for item in invocation["transformers"]
        )
        invocation["writer_callback_wall_sum_ns"] = sum(
            item.get("elapsed_ns", 0)
            for item in invocation["callbacks"]
            if item.get("stage") in {"writer_create", "writer_accept", "writer_to_bytes"}
        )

    if accept_index is None:
        raise ValueError("transform_accept is outside all target ClassTransformer invocations")
    accept_invocation = invocations[accept_index]
    containing_strict = [
        item
        for item in accept_invocation["strict_transformers"]
        if item.get("start_ns", accept_ns + 1) <= accept_ns <= item.get("end_ns", accept_ns - 1)
    ]
    if len(containing_strict) != 1:
        raise ValueError(f"expected one strict callback containing transform_accept, found {len(containing_strict)}")

    before_entry = [item for item in invocations if item["return_ns"] <= entry_ns]
    if not before_entry:
        raise ValueError("no completed target transform before bootstrap_entry")
    entry_nearest = max(before_entry, key=lambda item: item["return_ns"])
    next_invocation = invocations[accept_index + 1] if accept_index + 1 < len(invocations) else None

    result = {
        "schema": 2,
        "metric_type": "single-run monotonic target trace; not an A/B performance result",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu_reached_before_any_later_validity_gate",
        "target": TARGET,
        "runtime_identity": identities[0],
        "accept_ns": accept_ns,
        "bootstrap_entry_ns": entry_ns,
        "accept_to_entry_ns": entry_ns - accept_ns,
        "target_transform_invocation_count": len(invocations),
        "accept_invocation_index": accept_index,
        "entry_nearest_invocation_index": entry_nearest["index"],
        "accept_to_accept_invocation_return_ns": accept_invocation["return_ns"] - accept_ns,
        "accept_invocation_return_to_bootstrap_entry_ns": entry_ns - accept_invocation["return_ns"],
        "entry_nearest_invocation_return_to_bootstrap_entry_ns": entry_ns - entry_nearest["return_ns"],
        "invocations": invocations,
    }
    if next_invocation is not None:
        gap = next_invocation["begin_ns"] - accept_invocation["return_ns"]
        if gap < 0:
            raise ValueError("next target invocation overlaps accept invocation")
        result["next_target_transform_index"] = next_invocation["index"]
        result["accept_invocation_return_to_next_target_transform_begin_ns"] = gap
        result["accept_invocation_return_to_next_target_transform_begin_fraction"] = gap / (entry_ns - accept_ns)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = parse_profile(args.console.read_text(encoding="utf-8", errors="replace"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_ML_FORK_PROFILE_V2 " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
