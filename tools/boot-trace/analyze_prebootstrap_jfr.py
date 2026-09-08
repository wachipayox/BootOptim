#!/usr/bin/env python3
"""Attribute CPU samples inside structured pre-Bootstrap phase windows.

Diagnostic-only helper. It never runs in Minecraft. The exact-pack harness may invoke it
after the JVM exits when an opt-in JFR file is present.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

PHASES = (
    "modlauncher_transformers_to_minecraft_bootstrap",
    "modlauncher_transformers_to_minecraft_bootstrap_transform_accept",
    "minecraft_bootstrap_transform_accept_to_entry",
)

# Exclusive full-stack ownership. More-specific transformation owners precede
# their generic ASM/JDK callees. Presence counters below remain non-exclusive.
STACK_CATEGORY_ORDER = (
    "mixin",
    "sinytra",
    "access_transformer",
    "fml_neoforge",
    "fml_loading",
    "modlauncher",
    "module_classloading",
    "datafixer",
    "minecraft",
    "jpms",
    "jdk_classloading",
    "asm",
    "zip_io",
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
    # datetime is microsecond-based, but JFR prints nanoseconds. Preserve the
    # fractional tail explicitly so phase-edge attribution does not lose it.
    match = re.fullmatch(r"(.+?\.)(\d+)(Z|[+-]\d\d:\d\d)", text)
    if match:
        fraction_ns = int((match.group(2) + "000000000")[:9])
        base_text = match.group(1)[:-1] + ("+00:00" if match.group(3) == "Z" else match.group(3))
        base = datetime.fromisoformat(base_text)
        if base.tzinfo is None:
            base = base.replace(tzinfo=timezone.utc)
        return int(base.timestamp()) * 1_000_000_000 + fraction_ns
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
    # JFR JSON uses JVM-internal slash names for Java types.
    class_name = class_name.replace("/", ".")
    return f"{class_name}.{method_name}"


def category(name: str) -> str:
    """Conservative top-frame category retained for backwards-readable output."""
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


def stack_tags(names: list[str]) -> set[str]:
    tags: set[str] = set()
    for name in names:
        if name.startswith("org.spongepowered.asm."):
            tags.add("mixin")
        if name.startswith("org.sinytra."):
            tags.add("sinytra")
        if name.startswith("net.neoforged.accesstransformer."):
            tags.add("access_transformer")
        if name.startswith("net.neoforged.fml.common.asm.") or name.startswith("net.neoforged.neoforge."):
            tags.add("fml_neoforge")
        if name.startswith("net.neoforged.fml.loading."):
            tags.add("fml_loading")
        if name.startswith("cpw.mods.modlauncher."):
            tags.add("modlauncher")
        if name.startswith("cpw.mods.cl.") or name.startswith("cpw.mods.jarhandling."):
            tags.add("module_classloading")
        if name.startswith("com.mojang.datafixers."):
            tags.add("datafixer")
        if name.startswith("net.minecraft."):
            tags.add("minecraft")
        if name.startswith("java.lang.module.") or name.startswith("jdk.internal.module."):
            tags.add("jpms")
        if (name.startswith("java.lang.ClassLoader.") or name.startswith("jdk.internal.loader.")
                or name.startswith("java.lang.Class.") or name.startswith("java.lang.invoke.")):
            tags.add("jdk_classloading")
        if name.startswith("org.objectweb.asm."):
            tags.add("asm")
        if (name.startswith("jdk.nio.zipfs.") or name.startswith("java.util.zip.")
                or name.startswith("java.io.") or name.startswith("sun.nio.ch.")):
            tags.add("zip_io")
    return tags


def stack_category(names: list[str]) -> str:
    tags = stack_tags(names)
    for candidate in STACK_CATEGORY_ORDER:
        if candidate in tags:
            return candidate
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
        phase: {
            "samples": 0,
            "threads": Counter(),
            "categories": Counter(),
            "stack_categories": Counter(),
            "stack_presence": Counter(),
            "thread_stack_categories": defaultdict(Counter),
            "top_frames": Counter(),
        }
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
        names = [frame_name(frame) for frame in frames]
        top = names[0] if names else "<no-java-frame>"
        owner = stack_category(names)
        tags = stack_tags(names)
        sampled_thread = values.get("sampledThread") or {}
        thread = sampled_thread.get("javaName") or sampled_thread.get("osName") or "?"
        for phase, (begin_ns, end_ns) in windows.items():
            if begin_ns <= sample_ns <= end_ns:
                bucket = buckets[phase]
                bucket["samples"] += 1
                bucket["threads"][thread] += 1
                bucket["categories"][category(top)] += 1
                bucket["stack_categories"][owner] += 1
                bucket["thread_stack_categories"][thread][owner] += 1
                for tag in tags:
                    bucket["stack_presence"][tag] += 1
                bucket["top_frames"][top] += 1
    result = {"schema": "bootoptim.prebootstrap_jfr.v1", "phases": {}}
    for phase in PHASES:
        begin_ns, end_ns = windows[phase]
        bucket = buckets[phase]
        thread_categories = {
            thread: dict(bucket["thread_stack_categories"][thread].most_common())
            for thread, _ in bucket["threads"].most_common(12)
        }
        result["phases"][phase] = {
            "wall_ms": round((end_ns - begin_ns) / 1_000_000, 6),
            "execution_samples": bucket["samples"],
            "categories": dict(bucket["categories"].most_common()),
            "stack_categories": dict(bucket["stack_categories"].most_common()),
            "stack_presence": dict(bucket["stack_presence"].most_common()),
            "threads": dict(bucket["threads"].most_common(12)),
            "thread_stack_categories": thread_categories,
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
