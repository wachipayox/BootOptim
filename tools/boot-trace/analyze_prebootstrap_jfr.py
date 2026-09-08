#!/usr/bin/env python3
"""Attribute CPU samples inside structured pre-Bootstrap phase windows.

Diagnostic-only helper. It never runs in Minecraft. The exact-pack harness may invoke it
after the JVM exits when an opt-in JFR file is present.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

PHASES = (
    "modlauncher_transformers_to_minecraft_bootstrap",
    "modlauncher_transformers_to_minecraft_bootstrap_transform_accept",
    "minecraft_bootstrap_transform_accept_to_entry",
)


def load_phase_windows(trace_path: Path) -> dict[str, tuple[int, int]]:
    header = None
    begins: dict[str, int] = {}
    ends: dict[str, int] = {}
    for raw in trace_path.read_text(encoding="utf-8").splitlines():
        record = json.loads(raw)
        if record.get("record") == "trace_header":
            header = record
        elif record.get("record") == "event" and record.get("phase") in PHASES:
            phase = record["phase"]
            if record.get("type") == "phase_begin":
                begins[phase] = int(record["mono_ns"])
            elif record.get("type") == "phase_end":
                ends[phase] = int(record["mono_ns"])
    if header is None:
        raise ValueError("trace header missing")
    origin_ns = int(header["trace_origin_epoch_ms"]) * 1_000_000
    windows = {}
    for phase in PHASES:
        if phase not in begins or phase not in ends:
            raise ValueError(f"unbalanced phase: {phase}")
        windows[phase] = (origin_ns + begins[phase], origin_ns + ends[phase])
    return windows


def parse_time_ns(value: str) -> int:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1_000_000_000)


def frame_name(frame: dict) -> str:
    method = frame.get("method") or {}
    method_name = method.get("name") or "?"
    holder = method.get("type") or method.get("class") or {}
    class_name = holder.get("name") if isinstance(holder, dict) else str(holder)
    if not class_name:
        class_name = method.get("typeName") or method.get("className") or "?"
    return f"{class_name}.{method_name}"


def category(name: str) -> str:
    if name.startswith("org.spongepowered.asm."):
        return "mixin"
    if name.startswith("cpw.mods.modlauncher."):
        return "modlauncher"
    if name.startswith("org.objectweb.asm."):
        return "asm"
    if name.startswith("net.neoforged.fml.") or name.startswith("net.neoforged.neoforge."):
        return "fml_neoforge"
    if name.startswith("org.sinytra."):
        return "sinytra"
    if name.startswith("java.lang.ClassLoader.") or name.startswith("jdk.internal.loader.") or name.startswith("java.lang.invoke."):
        return "jdk_classloading"
    if name.startswith("com.mojang.datafixers."):
        return "datafixer"
    return "other"


def iter_events(document):
    if isinstance(document, dict):
        if document.get("type") == "jdk.ExecutionSample" and isinstance(document.get("values"), dict):
            yield document
        for value in document.values():
            yield from iter_events(value)
    elif isinstance(document, list):
        for value in document:
            yield from iter_events(value)


def summarize(jfr_json: dict, windows: dict[str, tuple[int, int]]) -> dict:
    buckets = {
        phase: {"samples": 0, "threads": Counter(), "categories": Counter(), "top_frames": Counter()}
        for phase in PHASES
    }
    for event in iter_events(jfr_json):
        values = event["values"]
        start = values.get("startTime")
        if not start:
            continue
        sample_ns = parse_time_ns(start)
        stack = values.get("stackTrace") or {}
        frames = stack.get("frames") or []
        top = frame_name(frames[0]) if frames else "<no-java-frame>"
        sampled_thread = values.get("sampledThread") or {}
        thread = sampled_thread.get("javaName") or sampled_thread.get("osName") or "?"
        for phase, (begin_ns, end_ns) in windows.items():
            if begin_ns <= sample_ns <= end_ns:
                bucket = buckets[phase]
                bucket["samples"] += 1
                bucket["threads"][thread] += 1
                bucket["categories"][category(top)] += 1
                bucket["top_frames"][top] += 1
    result = {"schema": "bootoptim.prebootstrap_jfr.v1", "phases": {}}
    for phase in PHASES:
        begin_ns, end_ns = windows[phase]
        bucket = buckets[phase]
        result["phases"][phase] = {
            "wall_ms": round((end_ns - begin_ns) / 1_000_000, 6),
            "execution_samples": bucket["samples"],
            "categories": dict(bucket["categories"].most_common()),
            "threads": dict(bucket["threads"].most_common(12)),
            "top_frames": [{"frame": k, "samples": v} for k, v in bucket["top_frames"].most_common(25)],
        }
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jfr", required=True, type=Path)
    parser.add_argument("--trace", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jfr-tool", default="jfr")
    args = parser.parse_args()

    windows = load_phase_windows(args.trace)
    with tempfile.NamedTemporaryFile("w+", encoding="utf-8", suffix=".json") as temp:
        proc = subprocess.run(
            [args.jfr_tool, "print", "--json", "--events", "jdk.ExecutionSample", str(args.jfr)],
            stdout=temp,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            raise SystemExit(f"jfr print failed ({proc.returncode}): {proc.stderr.strip()}")
        temp.seek(0)
        data = json.load(temp)
    result = summarize(data, windows)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
