#!/usr/bin/env python3
import argparse
import json
from collections import defaultdict
from pathlib import Path


def ms(ns):
    return ns / 1_000_000.0


def one(events, kind):
    rows = [e for e in events if e["kind"] == kind]
    if len(rows) != 1:
        raise SystemExit(f"expected one {kind}, got {len(rows)}")
    return rows[0]


def cpu_sum(rows):
    vals = [e["cpu_ns"] for e in rows if e.get("cpu_ns", -1) >= 0]
    return sum(vals) if vals else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", required=True)
    ap.add_argument("--json-output", required=True)
    ap.add_argument("--markdown-output", required=True)
    args = ap.parse_args()

    events = [json.loads(line) for line in Path(args.trace).read_text().splitlines() if line.strip()]
    header = one(events, "profile_header")
    if (header.get("module_name") != "fml_loader" or header.get("module_version") != "4.0.43"
            or "accepted=true" not in (header.get("detail") or "")):
        raise SystemExit(f"profile version gate failed: {header}")

    stage2 = one(events, "stage2_validation")
    add = one(events, "add_for_scanning")
    barrier = one(events, "scan_barrier_wait")
    submits = [e for e in events if e["kind"] == "submit_for_scanning"]
    completions = [e for e in events if e["kind"] == "add_completed_file"]
    compiles = [e for e in events if e["kind"] == "compile_content"]
    if not submits or not completions:
        raise SystemExit(f"missing scan lifecycle coverage submits={len(submits)} completions={len(completions)}")
    if len(submits) != len(completions):
        raise SystemExit(f"scan lifecycle imbalance submits={len(submits)} completions={len(completions)}")

    first_submit = min(e["start_ns"] for e in submits)
    last_completion = max(e["end_ns"] for e in completions)
    completion_before_barrier = sum(e["end_ns"] <= barrier["start_ns"] for e in completions)
    completion_at_or_after_barrier = len(completions) - completion_before_barrier
    post_stage2_overlap = max(0, last_completion - stage2["end_ns"])
    completion_slack = barrier["start_ns"] - last_completion
    launch_to_last_completion = last_completion - first_submit
    stage2_to_barrier = barrier["start_ns"] - stage2["end_ns"]

    completion_workers = defaultdict(lambda: {"count": 0, "callback_wall_ns": 0, "callback_cpu_ns": 0})
    for e in completions:
        w = completion_workers[e["thread"]]
        w["count"] += 1
        w["callback_wall_ns"] += e["duration_ns"]
        if e.get("cpu_ns", -1) >= 0:
            w["callback_cpu_ns"] += e["cpu_ns"]

    submit_cpu = cpu_sum(submits)
    completion_cpu = cpu_sum(completions)
    summary = {
        "fml_version": header["module_version"],
        "stage2_wall_ms": ms(stage2["duration_ns"]),
        "stage2_cpu_ms": ms(stage2["cpu_ns"]) if stage2["cpu_ns"] >= 0 else None,
        "add_for_scanning_wall_ms": ms(add["duration_ns"]),
        "add_for_scanning_cpu_ms": ms(add["cpu_ns"]) if add["cpu_ns"] >= 0 else None,
        "submit_count": len(submits),
        "submit_wall_sum_ms": ms(sum(e["duration_ns"] for e in submits)),
        "submit_cpu_sum_ms": ms(submit_cpu) if submit_cpu is not None else None,
        "completion_count": len(completions),
        "completion_callback_wall_sum_ms": ms(sum(e["duration_ns"] for e in completions)),
        "completion_callback_cpu_sum_ms": ms(completion_cpu) if completion_cpu is not None else None,
        "compile_content_observed": bool(compiles),
        "compile_content_count": len(compiles),
        "compile_content_cpu_ms": (ms(cpu_sum(compiles)) if cpu_sum(compiles) is not None else None),
        "scan_submit_to_last_completion_envelope_ms": ms(launch_to_last_completion),
        "stage2_exit_to_last_completion_ms": ms(post_stage2_overlap),
        "stage2_exit_to_barrier_entry_ms": ms(stage2_to_barrier),
        "last_completion_before_barrier_ms": ms(completion_slack),
        "completion_callbacks_before_barrier": completion_before_barrier,
        "completion_callbacks_at_or_after_barrier": completion_at_or_after_barrier,
        "barrier_wall_ms": ms(barrier["duration_ns"]),
        "barrier_cpu_ms": ms(barrier["cpu_ns"]) if barrier["cpu_ns"] >= 0 else None,
        "scan_wait_critical_ms": 0.0 if completion_at_or_after_barrier == 0 else None,
        "completion_workers": {name: {
            "count": v["count"],
            "callback_wall_sum_ms": ms(v["callback_wall_ns"]),
            "callback_cpu_sum_ms": ms(v["callback_cpu_ns"]),
        } for name, v in sorted(completion_workers.items())},
    }
    Path(args.json_output).write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")

    compile_note = (f"observed {len(compiles)} calls" if compiles else
                    "not observed: ModFile was not transform-visible to this javaagent; worker scan CPU is intentionally not inferred")
    lines = [
        "# Agent140 FML background scan profile", "",
        "| scope | wall ms | CPU ms |", "|---|---:|---:|",
        f"| stage2Validation | {summary['stage2_wall_ms']:.3f} | {summary['stage2_cpu_ms']} |",
        f"| addForScanning | {summary['add_for_scanning_wall_ms']:.3f} | {summary['add_for_scanning_cpu_ms']} |",
        f"| submitForScanning sum ({len(submits)}) | {summary['submit_wall_sum_ms']:.3f} | {summary['submit_cpu_sum_ms']} |",
        f"| addCompletedFile callbacks sum ({len(completions)}) | {summary['completion_callback_wall_sum_ms']:.3f} | {summary['completion_callback_cpu_sum_ms']} |",
        f"| waitForScanToComplete barrier | {summary['barrier_wall_ms']:.3f} | {summary['barrier_cpu_ms']} |",
        "",
        f"Submission -> last completion callback envelope: **{summary['scan_submit_to_last_completion_envelope_ms']:.3f} ms**.",
        f"Stage2 exit -> last completion: **{summary['stage2_exit_to_last_completion_ms']:.3f} ms**.",
        f"Stage2 exit -> barrier entry: **{summary['stage2_exit_to_barrier_entry_ms']:.3f} ms**.",
        f"Last completion preceded barrier entry by **{summary['last_completion_before_barrier_ms']:.3f} ms**.",
        f"Completion callbacks before barrier: **{completion_before_barrier}/{len(completions)}**; at/after entry: **{completion_at_or_after_barrier}**.",
        f"Direct scan-wait critical contribution at the barrier: **{summary['scan_wait_critical_ms']} ms** (barrier method overhead reported separately).",
        f"compileContent coverage: **{compile_note}**.", "",
        "## Completion callback workers", "",
        "| worker | completions | callback wall sum ms | callback CPU sum ms |", "|---|---:|---:|---:|",
    ]
    for name, v in summary["completion_workers"].items():
        lines.append(f"| `{name}` | {v['count']} | {v['callback_wall_sum_ms']:.3f} | {v['callback_cpu_sum_ms']:.3f} |")
    Path(args.markdown_output).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
