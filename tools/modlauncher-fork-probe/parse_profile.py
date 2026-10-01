#!/usr/bin/env python3
"""Parse Agent 94 target-only ModLauncher fork trace without relaxing exact-pack validity gates."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TARGET = "net.minecraft.server.Bootstrap"
STRICT_LABEL = "boot_optim_agent94_bootstrap_profile"
EXPECTED_REQUESTS = (
    {
        "origin": "securejar_get_maybe_transformed_bytes",
        "raw_context": "mixin",
        "effective_reason": "mixin",
        "caller": "org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode",
    },
    {
        "origin": "securejar_reader_to_class",
        "raw_context": "null",
        "effective_reason": "classloading",
        "caller": "net.minecraft.client.main.Main#lambda$main$0",
    },
)


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
    request_id = _int(data, "request_id")
    if request_id is not None:
        result["request_id"] = request_id
    for key in ("start_ns", "end_ns", "elapsed_ns", "mono_ns"):
        value = _int(data, key)
        if value is not None:
            result[key] = value
    if data.get("owner") is not None:
        result["owner"] = data["owner"]
    if data.get("labels") is not None:
        result["labels"] = data["labels"]
    return result


def _request(data: dict[str, str], line_number: int) -> dict:
    result = {
        "request_id": int(data["request_id"]),
        "line": line_number,
        "origin": data.get("origin"),
        "raw_context": data.get("raw_context"),
        "effective_reason": data.get("effective_reason"),
        "thread": data.get("thread"),
        "loader_class": data.get("loader_class"),
        "loader_name": data.get("loader_name"),
        "loader_id": _int(data, "loader_id"),
        "target_module": data.get("target_module"),
        "tccl_class": data.get("tccl_class"),
        "tccl_name": data.get("tccl_name"),
        "tccl_id": _int(data, "tccl_id"),
        "caller": data.get("caller"),
        "caller_module": data.get("caller_module"),
        "caller_source": data.get("caller_source"),
        "stack": data.get("stack"),
    }
    return result


def parse_profile(console_text: str) -> dict:
    identities: list[dict[str, str]] = []
    requests: list[dict] = []
    request_ends: dict[int, dict] = {}
    accept_ns: int | None = None
    entry_ns: int | None = None
    open_by_request: dict[int, dict] = {}
    invocations: list[dict] = []

    for line_number, line in enumerate(console_text.splitlines(), start=1):
        if "BOOTOPTIM_ML_FORK_IDENTITY" in line:
            identities.append(parse_kv(line))
            continue
        if "BOOTOPTIM_ML_FORK_REQUEST_END " in line:
            data = parse_kv(line)
            if data.get("class") != TARGET:
                continue
            request_id = int(data["request_id"])
            if request_id in request_ends:
                raise ValueError(f"duplicate request end for request {request_id}")
            request_ends[request_id] = {
                "request_id": request_id,
                "observed_id": int(data["observed_id"]),
                "line": line_number,
                "mono_ns": int(data["mono_ns"]),
                "thread": data.get("thread"),
            }
            continue
        if "BOOTOPTIM_ML_FORK_REQUEST " in line:
            data = parse_kv(line)
            if data.get("class") == TARGET:
                requests.append(_request(data, line_number))
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
        request_id = _int(data, "request_id")
        if request_id is None or request_id <= 0:
            raise ValueError(f"target stage missing request_id at line {line_number}")
        labels_match = re.search(r"labels=(\[.*?\])(?:\s|$)", line)
        if labels_match:
            data["labels"] = labels_match.group(1)
        stage = data.get("stage")
        thread = data.get("thread", "<unknown>")

        if stage == "class_transform_begin":
            if request_id in open_by_request:
                raise ValueError(f"nested target transform for request {request_id}")
            invocation = {
                "request_id": request_id,
                "thread": thread,
                "begin_ns": int(data["mono_ns"]),
                "begin_line": line_number,
                "callbacks": [],
                "transformers": [],
            }
            open_by_request[request_id] = invocation
            invocations.append(invocation)
            continue

        invocation = open_by_request.get(request_id)
        if invocation is None:
            raise ValueError(f"target stage {stage!r} without open invocation for request {request_id}")
        if invocation["thread"] != thread:
            raise ValueError(f"request {request_id} ClassTransformer changed thread")
        if stage == "class_transform_return":
            invocation["return_ns"] = int(data["mono_ns"])
            invocation["return_line"] = line_number
            open_by_request.pop(request_id)
            continue

        item = _callback(data)
        if stage == "transformer":
            invocation["transformers"].append(item)
        else:
            invocation["callbacks"].append(item)

    if open_by_request:
        raise ValueError(f"unterminated target transforms: {sorted(open_by_request)}")
    if accept_ns is None or entry_ns is None:
        raise ValueError("missing strict transform_accept/bootstrap_entry boundary")
    if entry_ns <= accept_ns:
        raise ValueError("non-positive accept_to_entry interval")
    if len(identities) != 1 or identities[0].get("module") != "cpw.mods.modlauncher":
        raise ValueError(f"runtime identity invalid: {identities}")

    if len(requests) != 2:
        raise ValueError(f"expected exactly two Bootstrap transform requests, found {len(requests)}")
    request_ids = [item["request_id"] for item in requests]
    if request_ids != [1, 2]:
        raise ValueError(f"expected Bootstrap request ids [1, 2] in order, found {request_ids}")
    for index, (request, expected) in enumerate(zip(requests, EXPECTED_REQUESTS), start=1):
        for key, value in expected.items():
            if request.get(key) != value:
                raise ValueError(
                    f"Bootstrap request {index} {key} mismatch: expected {value!r}, found {request.get(key)!r}"
                )
        if request.get("loader_class") != "cpw.mods.modlauncher.TransformingClassLoader":
            raise ValueError(f"Bootstrap request {index} has unexpected loader_class {request.get('loader_class')!r}")
        if request.get("loader_name") != "TRANSFORMER":
            raise ValueError(f"Bootstrap request {index} has unexpected loader_name {request.get('loader_name')!r}")
        if request.get("target_module") != "minecraft":
            raise ValueError(f"Bootstrap request {index} has unexpected target_module {request.get('target_module')!r}")
        end = request_ends.get(request["request_id"])
        if end is None:
            raise ValueError(f"Bootstrap request {index} missing request end")
        if end["observed_id"] != request["request_id"]:
            raise ValueError(f"Bootstrap request {index} request-stack mismatch: {end}")
        if end["thread"] != request["thread"]:
            raise ValueError(f"Bootstrap request {index} changed thread before request end")
        request["end_line"] = end["line"]
        request["end_ns"] = end["mono_ns"]
    if set(request_ends) != {1, 2}:
        raise ValueError(f"unexpected Bootstrap request ends: {sorted(request_ends)}")
    if requests[0]["line"] >= requests[0]["end_line"] or requests[0]["end_line"] >= requests[1]["line"]:
        raise ValueError("Bootstrap request order is not Mixin request complete -> classloading request")
    if requests[0]["loader_id"] != requests[1]["loader_id"]:
        raise ValueError("Bootstrap requests used different TransformingClassLoader instances")
    if requests[0]["tccl_id"] != requests[1]["tccl_id"]:
        raise ValueError("Bootstrap requests used different TCCL instances")

    if len(invocations) != 2:
        raise ValueError(f"expected exactly two Bootstrap ClassTransformer invocations, found {len(invocations)}")
    invocations.sort(key=lambda item: item["begin_ns"])
    if [item["request_id"] for item in invocations] != [1, 2]:
        raise ValueError(f"ClassTransformer invocation/request order mismatch: {[item['request_id'] for item in invocations]}")

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
        if len(invocation["strict_transformers"]) != 1:
            raise ValueError(
                f"expected exactly one strict Agent 94 callback in request {invocation['request_id']}, "
                f"found {len(invocation['strict_transformers'])}"
            )
        if invocation["contains_accept"]:
            if accept_index is not None:
                raise ValueError("transform_accept falls inside multiple target invocations")
            accept_index = index
        stages = {item["stage"]: item for item in invocation["callbacks"] if item.get("stage")}
        required = {"plugins_after", "writer_create", "writer_accept", "writer_to_bytes"}
        missing = sorted(required - set(stages))
        if missing:
            raise ValueError(f"request {invocation['request_id']} missing ClassTransformer stages: {missing}")
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

    if accept_index != 0:
        raise ValueError(f"transform_accept must occur in Mixin byte request 1, found invocation {accept_index}")
    accept_invocation = invocations[0]
    containing_strict = [
        item
        for item in accept_invocation["strict_transformers"]
        if item.get("start_ns", accept_ns + 1) <= accept_ns <= item.get("end_ns", accept_ns - 1)
    ]
    if len(containing_strict) != 1:
        raise ValueError(f"expected one strict callback containing transform_accept, found {len(containing_strict)}")

    classloading_invocation = invocations[1]
    if classloading_invocation["return_ns"] > entry_ns:
        raise ValueError("Bootstrap entry occurred before classloading transform returned")
    gap = classloading_invocation["begin_ns"] - accept_invocation["return_ns"]
    if gap < 0:
        raise ValueError("classloading request overlaps Mixin byte request")

    result = {
        "schema": 2,
        "metric_type": "single-run monotonic target trace; not an A/B performance result",
        "origin": "hosted_exact_pack",
        "endpoint": "main_menu_reached_before_any_later_validity_gate",
        "target": TARGET,
        "runtime_identity": identities[0],
        "request_contract": "mixin_transformed_bytes_then_classloading",
        "requests": requests,
        "accept_ns": accept_ns,
        "bootstrap_entry_ns": entry_ns,
        "accept_to_entry_ns": entry_ns - accept_ns,
        "target_transform_invocation_count": len(invocations),
        "accept_invocation_index": 0,
        "entry_nearest_invocation_index": 1,
        "accept_to_accept_invocation_return_ns": accept_invocation["return_ns"] - accept_ns,
        "accept_invocation_return_to_bootstrap_entry_ns": entry_ns - accept_invocation["return_ns"],
        "entry_nearest_invocation_return_to_bootstrap_entry_ns": entry_ns - classloading_invocation["return_ns"],
        "next_target_transform_index": 1,
        "accept_invocation_return_to_next_target_transform_begin_ns": gap,
        "accept_invocation_return_to_next_target_transform_begin_fraction": gap / (entry_ns - accept_ns),
        "invocations": invocations,
    }
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
