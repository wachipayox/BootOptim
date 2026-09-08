#!/usr/bin/env python3
"""Summarize JFR allocation samples attributable to the real BlockModel parse path.

This is diagnostic tooling only. JFR ObjectAllocationSample weights are sampled estimates,
not exact allocated-byte accounting and never TTMM. Exact current-thread allocated bytes
remain supplied by ModelClassReplay's ThreadMXBean phase metric.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

EVENT_RE = re.compile(r"jdk\.ObjectAllocationSample\s*\{(.*?)\n\}", re.S)
CLASS_RE = re.compile(r"^\s*objectClass\s*=\s*(.+?)\s*$", re.M)
WEIGHT_RE = re.compile(r"^\s*weight\s*=\s*([0-9.]+)\s*([KMGT]?B)\s*$", re.M | re.I)
STACK_RE = re.compile(r"^\s*stackTrace\s*=\s*\[(.*?)^\s*\]\s*$", re.M | re.S)

TARGET_FRAMES = (
    "net.minecraft.client.renderer.block.model.BlockModel.fromStream",
    "net.minecraft.client.renderer.block.model.BlockModel$Deserializer",
    "net.neoforged.neoforge.client.model.geometry.ExtendedBlockModelDeserializer",
    "net.neoforged.neoforge.client.model.ExtendedBlockModelDeserializer",
)
GROWTH_FRAMES = (
    "java.util.ArrayList.grow",
    "java.util.HashMap.resize",
    "java.util.Vector.grow",
)
COLLECTION_FRAMES = (
    "java.util.ArrayList.",
    "java.util.HashMap.",
    "java.util.LinkedHashMap.",
    "com.google.gson.internal.LinkedTreeMap.",
)
GSON_FRAMES = ("com.google.gson.",)


def parse_bytes(number: str, unit: str) -> int:
    factors = {"B": 1, "KB": 1024, "MB": 1024**2, "GB": 1024**3, "TB": 1024**4}
    return int(float(number) * factors[unit.upper()])


def top_frame(stack: str) -> str:
    for line in stack.splitlines():
        line = line.strip()
        if line:
            return line
    return "<unknown>"


def pct(part: int, whole: int) -> float:
    return 0.0 if whole <= 0 else part * 100.0 / whole


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jfr-text", type=Path, required=True)
    ap.add_argument("--replay-json", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()

    text = args.jfr_text.read_text(errors="replace")
    replay = json.loads(args.replay_json.read_text())

    total_weight = 0
    total_samples = 0
    target_weight = 0
    target_samples = 0
    growth_weight = 0
    growth_samples = 0
    collection_weight = 0
    gson_weight = 0
    by_class: Counter[str] = Counter()
    by_top: Counter[str] = Counter()
    growth_by_top: Counter[str] = Counter()

    for block in EVENT_RE.findall(text):
        cm = CLASS_RE.search(block)
        wm = WEIGHT_RE.search(block)
        sm = STACK_RE.search(block)
        if not cm or not wm or not sm:
            continue
        weight = parse_bytes(wm.group(1), wm.group(2))
        stack = sm.group(1)
        klass = cm.group(1).strip()
        total_weight += weight
        total_samples += 1
        if not any(frame in stack for frame in TARGET_FRAMES):
            continue
        target_weight += weight
        target_samples += 1
        by_class[klass] += weight
        by_top[top_frame(stack)] += weight
        if any(frame in stack for frame in GROWTH_FRAMES):
            growth_weight += weight
            growth_samples += 1
            growth_by_top[top_frame(stack)] += weight
        if any(frame in stack for frame in COLLECTION_FRAMES):
            collection_weight += weight
        if any(frame in stack for frame in GSON_FRAMES):
            gson_weight += weight

    semantic = replay.get("semantic", {})
    models = semantic.get("models", [])
    element_count = sum(len(model.get("elements", [])) for model in models)
    face_count = sum(
        len(element.get("faces", []))
        for model in models
        for element in model.get("elements", [])
    )
    exact_parse_alloc = replay["stock_1"]["phases"]["real_blockmodel_parse"].get("allocated_bytes", -1)

    result = {
        "schema": 1,
        "origin": "hosted exact-pack real-class replay JFR; not TTMM",
        "jfr_metric": "jdk.ObjectAllocationSample weight; sampled estimate, not exact allocation accounting",
        "semantic_sha256": replay.get("semantic_sha256"),
        "replay_scope": {
            "models": len(models),
            "elements": element_count,
            "faces": face_count,
            "stock_1_exact_current_thread_allocated_bytes": exact_parse_alloc,
        },
        "all_jfr": {"samples": total_samples, "sample_weight_bytes": total_weight},
        "deserializer_parse_path": {
            "samples": target_samples,
            "sample_weight_bytes": target_weight,
            "collection_stack_weight_bytes": collection_weight,
            "collection_stack_percent": pct(collection_weight, target_weight),
            "growth_resize_weight_bytes": growth_weight,
            "growth_resize_samples": growth_samples,
            "growth_resize_percent": pct(growth_weight, target_weight),
            "gson_stack_weight_bytes": gson_weight,
            "gson_stack_percent": pct(gson_weight, target_weight),
        },
        "top_object_classes_by_weight": by_class.most_common(30),
        "top_allocation_frames_by_weight": by_top.most_common(30),
        "growth_resize_frames_by_weight": growth_by_top.most_common(30),
        "decision_hint": (
            "growth_material" if target_weight and pct(growth_weight, target_weight) >= 10.0
            else "growth_not_material"
        ),
        "decision_threshold_note": "10% JFR parse-path sample weight is a diagnostic escalation threshold, not a production acceptance threshold",
    }
    if target_samples == 0:
        raise SystemExit("No ObjectAllocationSample events were attributable to BlockModel.fromStream/deserializer stacks")
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
