#!/usr/bin/env python3
import argparse
import json
from collections import defaultdict
from pathlib import Path


def ms(ns):
    return ns / 1_000_000.0


def merge(intervals):
    out = []
    for start, end in sorted(intervals):
        if not out or start > out[-1][1]:
            out.append([start, end])
        else:
            out[-1][1] = max(out[-1][1], end)
    return out


def overlap_ns(intervals, start, end):
    if end <= start:
        return 0
    total = 0
    for a, b in merge(intervals):
        total += max(0, min(b, end) - max(a, start))
    return total


def one(events, kind):
    rows = [e for e in events if e["kind"] == kind]
    if len(rows) != 1:
        raise SystemExit(f"expected one {kind}, got {len(rows)}")
    return rows[0]


def cpu_sum(rows):
    vals = [e["cpu_ns"] for e in rows if e.get("cpu_ns", -1) >= 0]
    return sum(vals) if vals else -1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", required=True)
    ap.add_argument("--json-output", required=True)
    ap.add_argument("--markdown-output", required=True)
    args = ap.parse_args()

    events = [json.loads(line) for line in Path(args.trace).read_text().splitlines() if line.strip()]
    header = one(events, "profile_header")
    if header.get("module_name") != "fml_loader" or header.get("module_version") != "4.0.43" or "accepted=true" not in (header.get("detail") or ""):
        raise SystemExit(f"profile version gate failed: {header}")

    stage2 = one(events, "stage2_validation")
    add = one(events, "add_for_scanning")
    barrier = one(events, "scan_barrier_wait")
    submits = [e for e in events if e["kind"] == "submit_for_scanning"]
    compiles = [e for e in events if e["kind"] == "compile_content"]
    gets = [e for e in events if e["kind"] == "get_scan_result"]
    if not compiles:
        raise SystemExit("no compile_content events")

    intervals = [(e["start_ns"], e["end_ns"]) for e in compiles]
    first_scan = min(e["start_ns"] for e in compiles)
    last_scan = max(e["end_ns"] for e in compiles)
    completed_before_barrier = sum(e["end_ns"] <= barrier["start_ns"] for e in compiles)
    started_before_barrier = sum(e["start_ns"] < barrier["start_ns"] for e in compiles)
    running_at_barrier = sum(e["start_ns"] < barrier["start_ns"] < e["end_ns"] for e in compiles)
    post_stage2_gap = max(0, barrier["start_ns"] - stage2["end_ns"])
    post_stage2_active = overlap_ns(intervals, stage2["end_ns"], barrier["start_ns"])
    barrier_active = overlap_ns(intervals, barrier["start_ns"], barrier["end_ns"])
    unfinished_to_last = max(0, last_scan - barrier["start_ns"])
    poll_tail = max(0, barrier["end_ns"] - max(barrier["start_ns"], last_scan))
    early_gets = [e for e in gets if e["start_ns"] < barrier["start_ns"]]

    workers = defaultdict(lambda: {"count": 0, "wall_ns": 0, "cpu_ns": 0, "first_ns": None, "last_ns": None})
    for e in compiles:
        w = workers[e["thread"]]
        w["count"] += 1
        w["wall_ns"] += e["duration_ns"]
        if e.get("cpu_ns", -1) >= 0:
            w["cpu_ns"] += e["cpu_ns"]
        w["first_ns"] = e["start_ns"] if w["first_ns"] is None else min(w["first_ns"], e["start_ns"])
        w["last_ns"] = e["end_ns"] if w["last_ns"] is None else max(w["last_ns"], e["end_ns"])

    summary = {
        "fml_version": header["module_version"],
        "stage2_wall_ms": ms(stage2["duration_ns"]),
        "stage2_cpu_ms": ms(stage2["cpu_ns"]) if stage2["cpu_ns"] >= 0 else None,
        "add_for_scanning_wall_ms": ms(add["duration_ns"]),
        "add_for_scanning_cpu_ms": ms(add["cpu_ns"]) if add["cpu_ns"] >= 0 else None,
        "submit_count": len(submits),
        "submit_wall_sum_ms": ms(sum(e["duration_ns"] for e in submits)),
        "submit_cpu_sum_ms": ms(cpu_sum(submits)) if cpu_sum(submits) >= 0 else None,
        "compile_count": len(compiles),
        "compile_wall_sum_ms": ms(sum(e["duration_ns"] for e in compiles)),
        "compile_cpu_sum_ms": ms(cpu_sum(compiles)) if cpu_sum(compiles) >= 0 else None,
        "scan_active_union_ms": ms(sum(b - a for a, b in merge(intervals))),
        "scan_envelope_ms": ms(last_scan - first_scan),
        "post_stage2_to_barrier_wall_ms": ms(post_stage2_gap),
        "scan_active_during_post_stage2_gap_ms": ms(post_stage2_active),
        "barrier_wall_ms": ms(barrier["duration_ns"]),
        "barrier_cpu_ms": ms(barrier["cpu_ns"]) if barrier["cpu_ns"] >= 0 else None,
        "scan_active_inside_barrier_ms": ms(barrier_active),
        "unfinished_scan_to_last_completion_ms": ms(unfinished_to_last),
        "barrier_poll_tail_ms": ms(poll_tail),
        "compile_completed_before_barrier": completed_before_barrier,
        "compile_started_before_barrier": started_before_barrier,
        "compile_running_at_barrier": running_at_barrier,
        "get_scan_result_count": len(gets),
        "get_scan_result_before_barrier_count": len(early_gets),
        "get_scan_result_before_barrier_wall_sum_ms": ms(sum(e["duration_ns"] for e in early_gets)),
        "workers": {name: {
            "count": v["count"],
            "wall_sum_ms": ms(v["wall_ns"]),
            "cpu_sum_ms": ms(v["cpu_ns"]),
            "envelope_ms": ms(v["last_ns"] - v["first_ns"]),
        } for name, v in sorted(workers.items())},
    }
    Path(args.json_output).write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")

    lines = [
        "# Agent140 FML background scan profile",
        "",
        "| scope | wall ms | CPU ms |",
        "|---|---:|---:|",
        f"| stage2Validation | {summary['stage2_wall_ms']:.3f} | {summary['stage2_cpu_ms'] if summary['stage2_cpu_ms'] is not None else 'n/a'} |",
        f"| addForScanning | {summary['add_for_scanning_wall_ms']:.3f} | {summary['add_for_scanning_cpu_ms'] if summary['add_for_scanning_cpu_ms'] is not None else 'n/a'} |",
        f"| submitForScanning sum ({len(submits)}) | {summary['submit_wall_sum_ms']:.3f} | {summary['submit_cpu_sum_ms'] if summary['submit_cpu_sum_ms'] is not None else 'n/a'} |",
        f"| compileContent task sum ({len(compiles)}) | {summary['compile_wall_sum_ms']:.3f} | {summary['compile_cpu_sum_ms'] if summary['compile_cpu_sum_ms'] is not None else 'n/a'} |",
        f"| scan active union | {summary['scan_active_union_ms']:.3f} | n/a |",
        f"| stage2 exit -> scan barrier entry | {summary['post_stage2_to_barrier_wall_ms']:.3f} | n/a |",
        f"| scan active within that overlap window | {summary['scan_active_during_post_stage2_gap_ms']:.3f} | n/a |",
        f"| waitForScanToComplete barrier | {summary['barrier_wall_ms']:.3f} | {summary['barrier_cpu_ms'] if summary['barrier_cpu_ms'] is not None else 'n/a'} |",
        f"| unfinished scan: barrier entry -> last compile completion | {summary['unfinished_scan_to_last_completion_ms']:.3f} | n/a |",
        f"| scan-active union inside barrier | {summary['scan_active_inside_barrier_ms']:.3f} | n/a |",
        f"| post-completion polling tail | {summary['barrier_poll_tail_ms']:.3f} | n/a |",
        "",
        f"compileContent completions before barrier: **{completed_before_barrier}/{len(compiles)}**; running at entry: **{running_at_barrier}**.",
        f"getScanResult calls before barrier: **{len(early_gets)}** (wall sum {summary['get_scan_result_before_barrier_wall_sum_ms']:.3f} ms).",
        "",
        "## Workers",
        "",
        "| worker | tasks | wall sum ms | CPU sum ms | envelope ms |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, v in summary["workers"].items():
        lines.append(f"| `{name}` | {v['count']} | {v['wall_sum_ms']:.3f} | {v['cpu_sum_ms']:.3f} | {v['envelope_ms']:.3f} |")
    Path(args.markdown_output).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
