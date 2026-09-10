#!/usr/bin/env python3
"""Correlate default-off P0.2 JVM snapshots with the external Windows host probe.

This parser never reads the live instance. It consumes completed evidence only and deliberately
reports covariates rather than naming a root cause from one run.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
from datetime import datetime
from pathlib import Path

P02 = "BOOTOPTIM_P02_JVM "
PAIR = re.compile(r"([A-Za-z0-9_]+)=([^\s]+)")
PHASE = re.compile(r"^PHASE name=(\S+) uptime_ms=(\d+)")
SUMMARY = re.compile(r"^SUMMARY .* total_startup_ms=(\d+) status=(\S+)")


def epoch_ms(text: str) -> float:
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp() * 1000.0


def num(value):
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def read_host(path: Path):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    headers = [r for r in rows if r.get("kind") == "header"]
    footers = [r for r in rows if r.get("kind") == "footer"]
    samples = [r for r in rows if r.get("kind") == "sample"]
    if len(headers) != 1 or len(footers) != 1:
        raise ValueError("host probe must contain exactly one header and one footer")
    return headers[0], samples, footers[0]


def read_console(path: Path):
    snapshots = []
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        if P02 not in line:
            continue
        tail = line.split(P02, 1)[1]
        row = {m.group(1): m.group(2) for m in PAIR.finditer(tail)}
        snapshots.append(row)
    return snapshots


def read_startup(path: Path):
    phases = {}
    total = None
    status = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = PHASE.match(line)
        if m:
            phases[m.group(1)] = int(m.group(2))
        m = SUMMARY.match(line)
        if m:
            total, status = int(m.group(1)), m.group(2)
    return phases, total, status


def mean_max(samples, key):
    vals = [num(s.get(key)) for s in samples]
    vals = [v for v in vals if v is not None]
    return {"mean": statistics.fmean(vals), "max": max(vals)} if vals else None


def first_last(samples, key):
    vals = [num(s.get(key)) for s in samples]
    vals = [v for v in vals if v is not None]
    return {"first": vals[0], "last": vals[-1], "delta": vals[-1] - vals[0]} if vals else None


def aggregate(samples):
    keys = [
        "processPercentCpu", "processIoReadBytesPerSec", "processIoWriteBytesPerSec",
        "processPageFaultsPerSec", "memoryPagesInputPerSec", "memoryPageReadsPerSec",
        "memoryTransitionFaultsPerSec", "diskReadBytesPerSec", "diskWriteBytesPerSec",
        "diskCurrentQueueLength", "diskAvgQueueLength", "diskAvgSecondsPerRead",
        "diskPercentTime", "cpuPercent", "processorQueueLength", "sampleCostMs",
    ]
    out = {key: mean_max(samples, key) for key in keys}
    out["memoryAvailableMBytes"] = first_last(samples, "memoryAvailableMBytes")
    out["memoryCacheBytes"] = first_last(samples, "memoryCacheBytes")
    out["memoryStandbyCacheNormalPriorityBytes"] = first_last(samples, "memoryStandbyCacheNormalPriorityBytes")
    out["sampleCount"] = len(samples)
    return out


def snapshot_by_phase(snapshots, phase):
    found = [s for s in snapshots if s.get("phase") == phase]
    return found[0] if len(found) == 1 else None


def delta(a, b, key):
    x, y = num(a.get(key)), num(b.get(key))
    return None if x is None or y is None else y - x


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", required=True, type=Path)
    ap.add_argument("--console", required=True, type=Path)
    ap.add_argument("--startup", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path)
    args = ap.parse_args()

    header, host_samples, footer = read_host(args.host)
    snapshots = read_console(args.console)
    phases, total, startup_status = read_startup(args.startup)
    issues, warnings = [], []

    if header.get("measurementClass") != "diagnostic_not_benchmark":
        issues.append("host trace is not explicitly diagnostic_not_benchmark")
    if header.get("origin") not in {"physical_laptop", "hosted_windows_harness"}:
        issues.append("missing/unsupported measurement origin")
    if header.get("coldState") not in {"fresh_boot_no_pack_touch", "warm_same_boot", "unknown"}:
        issues.append("missing/unsupported cold-state provenance")
    if footer.get("status") != "completed":
        issues.append("host trace did not complete")
    if startup_status != "main_menu_reached":
        warnings.append(f"startup status is {startup_status!r}; verify endpoint semantics")

    early_candidates = [s for s in snapshots if s.get("phase", "").startswith("transformation_service_") and "error" not in s]
    early = min(early_candidates, key=lambda s: num(s.get("uptime_ms")) or float("inf")) if early_candidates else None
    entry = snapshot_by_phase(snapshots, "mod_entrypoint")
    if early is None:
        issues.append("missing successful early transformation-service P0.2 JVM snapshot")
    if entry is None or "error" in entry:
        issues.append("missing unique successful mod_entrypoint P0.2 JVM snapshot")

    starts = {s.get("jvm_start_epoch_ms") for s in snapshots if s.get("jvm_start_epoch_ms")}
    if len(starts) != 1:
        issues.append("P0.2 JVM snapshots do not share exactly one JVM start epoch")
    jvm_start = num(next(iter(starts))) if len(starts) == 1 else None
    entry_uptime = phases.get("mod_entrypoint")
    endpoint_name = header.get("endpoint")
    endpoint_uptime = phases.get(endpoint_name)
    if endpoint_name == "main_menu" and endpoint_uptime is None:
        endpoint_uptime = total
    if entry_uptime is None:
        issues.append("startup report lacks mod_entrypoint")
    if endpoint_uptime is None:
        issues.append(f"startup report lacks requested endpoint {endpoint_name}")

    first_rel = None
    if host_samples and jvm_start is not None:
        first_rel = epoch_ms(host_samples[0]["utc"]) - jvm_start
        if first_rel > 15000:
            issues.append(f"first host sample is {first_rel:.0f} ms after JVM start (>15000 ms)")
        if first_rel < -5000:
            warnings.append("host sampling began materially before recorded JVM start; verify clock provenance")
    elif not host_samples:
        issues.append("host trace has no samples")

    pre, post = [], []
    if jvm_start is not None and entry_uptime is not None:
        entry_epoch = jvm_start + entry_uptime
        end_epoch = None if endpoint_uptime is None else jvm_start + endpoint_uptime
        for sample in host_samples:
            t = epoch_ms(sample["utc"])
            if t <= entry_epoch:
                pre.append(sample)
            elif end_epoch is None or t <= end_epoch:
                post.append(sample)

    observer_cpu_ms = num(footer.get("observerCpuMs"))
    span_ms = None
    if host_samples:
        span_ms = epoch_ms(host_samples[-1]["utc"]) - epoch_ms(host_samples[0]["utc"])
    observer_one_core_fraction = None if not span_ms or observer_cpu_ms is None else observer_cpu_ms / span_ms
    max_cost = num(footer.get("maxSampleCostMs"))
    interval = num(header.get("sampleIntervalMs"))
    if observer_one_core_fraction is not None and observer_one_core_fraction > 0.02:
        warnings.append("observer consumed >2% of one logical CPU over sampled wall; do not use as low-noise evidence")
    if max_cost is not None and interval and max_cost > 0.20 * interval:
        warnings.append("a host sample cost exceeded 20% of the sampling interval")

    jvm_pre = None
    if early is not None and entry is not None:
        wall = delta(early, entry, "uptime_ms")
        cpu = delta(early, entry, "process_cpu_ms")
        jvm_pre = {
            "wall_ms": wall,
            "process_cpu_ms": cpu,
            "effective_process_cpu_cores": None if not wall or cpu is None else cpu / wall,
            "total_loaded_classes_delta": delta(early, entry, "total_loaded_classes"),
            "unloaded_classes_delta": delta(early, entry, "unloaded_classes"),
            "gc_count_delta": delta(early, entry, "gc_count"),
            "gc_time_ms_delta": delta(early, entry, "gc_time_ms"),
            "heap_used_mib_delta": delta(early, entry, "heap_used_mib"),
            "threads_delta": delta(early, entry, "threads"),
            "early_phase": early.get("phase"),
        }

    result = {
        "schema": 1,
        "scope": "diagnostic correlation only; rates/counters are covariates, not causal or savings attribution",
        "provenance": {
            "runId": header.get("runId"), "origin": header.get("origin"),
            "endpoint": endpoint_name, "coldState": header.get("coldState"),
            "windowsBootUtc": header.get("windowsBootUtc"), "jvmStartEpochMs": jvm_start,
        },
        "timing": {"mod_entrypoint_ms": entry_uptime, "endpoint_ms": endpoint_uptime, "first_host_sample_after_jvm_start_ms": first_rel},
        "jvm_pre_entry": jvm_pre,
        "host_pre_entry": aggregate(pre),
        "host_post_entry": aggregate(post),
        "observer": {"samples": len(host_samples), "observerCpuMs": observer_cpu_ms, "oneCoreFraction": observer_one_core_fraction, "maxSampleCostMs": max_cost},
        "validity": {"issues": issues, "warnings": warnings, "valid": not issues},
        "interpretation_rules": [
            "Do not call wall-minus-process-CPU disk time.",
            "PageFaults/sec alone does not prove hard faults; require PagesInput/PageReads plus disk evidence.",
            "Compare only runs with matching physical origin, endpoint, pack/JVM contract and coldState class.",
            "A single run can correlate signals but cannot establish a P0.2 root cause.",
        ],
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    if issues:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
