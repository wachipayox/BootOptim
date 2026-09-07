#!/usr/bin/env python3
"""Aggregate low-retention ModelManager JFR samples after a run has ended.

The parser consumes ``jfr print`` as a stream, so it never materializes the
large JSON representation of an allocation recording.  It reports sample
counts and allocation weights by the real startup boundaries; it is a
diagnostic aid, not a replacement for exclusive CPU accounting.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
from collections import defaultdict
from pathlib import Path
from typing import Iterable


_TIME_RE = re.compile(r"^\s*startTime = (\d{2}:\d{2}:\d{2}\.\d{3})")
_WEIGHT_RE = re.compile(r"^\s*weight = ([0-9][0-9.,]*)\s*(B|kB|MB|GB)\s*$")
_DOMAIN_MARKERS = {
    "model_manager": ("net.minecraft.client.resources.model.ModelManager",),
    "model_bakery": ("net.minecraft.client.resources.model.ModelBakery",),
    "block_model": (
        "net.minecraft.client.renderer.block.model.BlockModel",
        "net.minecraft.client.resources.model.BlockStateModelLoader",
    ),
    "neoforge_model_deserializer": (
        "net.neoforged.neoforge.client.model.ExtendedBlockModelDeserializer",
    ),
}


def _phase(sample_time: dt.datetime, start: dt.datetime, mod_ms: float, menu_ms: float) -> str:
    elapsed = (sample_time - start).total_seconds() * 1000.0
    if elapsed < 0:
        return "before_start"
    if elapsed < mod_ms:
        return "mod_entrypoint"
    if elapsed <= menu_ms:
        return "post_entrypoint_to_menu"
    return "after_menu"


def _parse_weight(lines: Iterable[str]) -> float:
    for line in lines:
        match = _WEIGHT_RE.match(line)
        if not match:
            continue
        # JFR's text renderer follows the host locale (the hosted runner used
        # a decimal comma).  A recording with a decimal point is also valid.
        value = float(match.group(1).replace(",", "."))
        return value * {"B": 1, "kB": 1024, "MB": 1024**2, "GB": 1024**3}[match.group(2)]
    return 0.0


def _parse_sample_time(lines: Iterable[str], date: dt.date) -> dt.datetime | None:
    for line in lines:
        match = _TIME_RE.match(line)
        if match:
            return dt.datetime.combine(date, dt.time.fromisoformat(match.group(1)))
    return None


def _empty_bucket() -> dict[str, float | int]:
    return {"samples": 0, "model_related_samples": 0, "weight_bytes": 0.0, "model_related_weight_bytes": 0.0}


def _parse_start_time(value: str) -> dt.datetime:
    # bootoptim-startup.log keeps nanoseconds; datetime accepts microseconds.
    normalized = value.strip().replace("Z", "+00:00")
    match = re.match(r"^(.*\.)(\d{6})\d+(Z|[+-]\d\d:\d\d)$", value.strip())
    if match:
        suffix = "+00:00" if match.group(3) == "Z" else match.group(3)
        normalized = f"{match.group(1)}{match.group(2)}{suffix}"
    return dt.datetime.fromisoformat(normalized).replace(tzinfo=None)


def _consume_event(
    event_name: str,
    lines: list[str],
    start: dt.datetime,
    mod_ms: float,
    menu_ms: float,
    buckets: dict[str, dict[str, float | int]],
    domains: dict[str, int],
) -> None:
    sample_time = _parse_sample_time(lines, start.date())
    if sample_time is None:
        return
    phase = _phase(sample_time, start, mod_ms, menu_ms)
    text = "\n".join(lines)
    related = any(marker in text for markers in _DOMAIN_MARKERS.values() for marker in markers)
    weight = _parse_weight(lines) if event_name == "jdk.ObjectAllocationSample" else 0.0
    bucket = buckets[f"{event_name}:{phase}"]
    bucket["samples"] += 1
    bucket["weight_bytes"] += weight
    if related:
        bucket["model_related_samples"] += 1
        bucket["model_related_weight_bytes"] += weight
    for domain, markers in _DOMAIN_MARKERS.items():
        if any(marker in text for marker in markers):
            domains[f"{event_name}:{phase}:{domain}"] += 1


def _stream_events(
    recording: Path,
    event_name: str,
    start: dt.datetime,
    mod_ms: float,
    menu_ms: float,
    buckets: dict[str, dict[str, float | int]],
    domains: dict[str, int],
) -> None:
    process = subprocess.Popen(
        ["jfr", "print", "--events", event_name, str(recording)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert process.stdout is not None
    current: list[str] | None = None
    for raw in process.stdout:
        line = raw.rstrip("\r\n")
        if line.startswith(f"{event_name} {{"):
            current = [line]
            continue
        if current is None:
            continue
        current.append(line)
        if not line.strip():
            _consume_event(event_name, current, start, mod_ms, menu_ms, buckets, domains)
            current = None
    if current:
        _consume_event(event_name, current, start, mod_ms, menu_ms, buckets, domains)
    stderr = process.stderr.read() if process.stderr else ""
    return_code = process.wait()
    if return_code:
        raise SystemExit(f"jfr print failed for {event_name}: {stderr.strip()}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recording", required=True, type=Path)
    parser.add_argument("--start-time", required=True, help="ISO timestamp from bootoptim-startup.log")
    parser.add_argument("--mod-entrypoint-ms", required=True, type=float)
    parser.add_argument("--main-menu-ms", required=True, type=float)
    parser.add_argument("--output", type=Path, help="optional JSON output path")
    args = parser.parse_args()

    start = _parse_start_time(args.start_time)
    buckets: dict[str, dict[str, float | int]] = defaultdict(_empty_bucket)
    domains: dict[str, int] = defaultdict(int)
    for event_name in ("jdk.ExecutionSample", "jdk.ObjectAllocationSample"):
        _stream_events(args.recording, event_name, start, args.mod_entrypoint_ms, args.main_menu_ms, buckets, domains)

    result = {
        "recording": str(args.recording),
        "startup": {
            "start_time": args.start_time,
            "mod_entrypoint_ms": args.mod_entrypoint_ms,
            "main_menu_ms": args.main_menu_ms,
        },
        "buckets": dict(sorted(buckets.items())),
        "domain_sample_counts": dict(sorted(domains.items())),
        "interpretation": "Counts and allocation weights are sampled evidence; they are not exclusive CPU accounting.",
    }
    serialized = json.dumps(result, indent=2, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
