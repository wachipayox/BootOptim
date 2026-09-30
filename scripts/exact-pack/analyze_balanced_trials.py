#!/usr/bin/env python3
"""Screen complete within-JVM reload blocks against same-JVM A/A noise.

This consumes explicit evidence; it neither launches Minecraft nor turns old
fresh-VM medians into paired data. See the research protocol for the schema.
"""

import argparse
import json
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path


METRICS = ("owner_cpu_ms", "reload_wall_ms", "gc_ms")
ORDERS = {("control", "candidate", "candidate", "control"),
          ("candidate", "control", "control", "candidate")}


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def analyze(data):
    issues = []
    if data.get("schema") != 1:
        issues.append("Unsupported evidence schema")
    contract = data.get("contract", {})
    for key in ("feature", "source_sha", "jar_sha256", "pack_sha256", "options_sha256",
                "jvm", "jvm_args_sha256", "owner_cpu_scope"):
        if not isinstance(contract.get(key), str) or not contract[key].strip():
            issues.append(f"Missing contract {key}")
    for key, length in (("source_sha", 40), ("jar_sha256", 64), ("pack_sha256", 64), ("options_sha256", 64), ("jvm_args_sha256", 64)):
        if not re.fullmatch(r"[0-9a-fA-F]{" + str(length) + "}", str(contract.get(key, ""))):
            issues.append(f"Invalid contract digest {key}")
    if not isinstance(contract.get("requires_same_mode_primer"), bool):
        issues.append("Declare whether candidate retains state across generations")
    if contract.get("origin") != "reload_invocation" or contract.get("endpoint") != "future_completion":
        issues.append("Reload origin/endpoint mismatch")
    for key in ("minimum_cpu_ms", "minimum_cpu_fraction", "wall_regression_ms", "wall_regression_fraction"):
        if not number(contract.get(key)):
            issues.append(f"Missing predeclared threshold {key}")
    campaigns = data.get("campaigns", [])
    if not isinstance(campaigns, list) or not campaigns:
        return {"valid": False, "issues": issues + ["No campaigns"], "decision": "invalid"}
    summaries = []
    seen_processes = set()
    for campaign in campaigns:
        cid = campaign.get("id", "unknown")
        identity = tuple(campaign.get(k) for k in ("host_id", "pid", "process_created"))
        if any(v is None or v == "" for v in identity) or identity in seen_processes:
            issues.append(f"{cid}: missing/duplicated JVM identity")
        seen_processes.add(identity)
        if campaign.get("contract") != contract:
            issues.append(f"{cid}: effective contract differs")
        steps = campaign.get("steps", [])
        groups = defaultdict(list)
        previous = None
        previous_block = None
        closed_blocks = set()
        for position, row in enumerate(steps):
            generation = row.get("generation")
            if not isinstance(generation, int) or isinstance(generation, bool) or generation < 1 or (previous is not None and generation != previous + 1):
                issues.append(f"{cid}: missing/duplicate/reordered resource generation")
            previous = generation
            if row.get("success") is not True or row.get("pack_selection_valid") is not True:
                issues.append(f"{cid}: failed generation or resource-pack fallback")
            if row.get("kind") not in ("warmup", "conditioning", "measurement", "calibration"):
                issues.append(f"{cid}: unknown step kind")
            if row.get("mode") not in ("control", "candidate"):
                issues.append(f"{cid}: unknown effective mode")
            if row.get("kind") in ("measurement", "calibration"):
                if not all(number(row.get(metric)) for metric in METRICS):
                    issues.append(f"{cid}: unavailable/invalid clocks")
                    continue
                if row.get("owner_calls") != 1:
                    issues.append(f"{cid}: CPU owner not called exactly once")
                if row.get("environment_valid") is not True:
                    issues.append(f"{cid}: environment contract failed")
                if not isinstance(row.get("block"), str):
                    issues.append(f"{cid}: missing block identity")
                current_block = (row.get("kind"), row.get("block"))
                if previous_block != current_block:
                    if current_block in closed_blocks:
                        issues.append(f"{cid}: interleaved/reopened observation block")
                    if previous_block is not None:
                        closed_blocks.add(previous_block)
                    previous_block = current_block
                groups[(row.get("kind"), row.get("block"))].append(row)
                if contract.get("requires_same_mode_primer") is True:
                    primer = steps[position - 1] if position else {}
                    if primer.get("kind") != "conditioning" or primer.get("mode") != row.get("mode"):
                        issues.append(f"{cid}: missing same-mode primer")
        if not steps or steps[0].get("kind") != "warmup" or campaign.get("complete") is not True:
            issues.append(f"{cid}: missing conditioning/terminal endpoint")
        blocks, sham = [], []
        for (kind, bid), rows in groups.items():
            labels = tuple(row.get("label") for row in rows)
            if len(rows) != 4 or labels not in ORDERS:
                issues.append(f"{cid}/{bid}: expected four counterbalanced observations")
                continue
            if any(row["mode"] != (row["label"] if kind == "measurement" else "control") for row in rows):
                issues.append(f"{cid}/{bid}: wrong mask or A/A is not all-control")
            delta = {m: statistics.mean(r[m] for r in rows if r["label"] == "candidate")
                     - statistics.mean(r[m] for r in rows if r["label"] == "control") for m in METRICS}
            item = {"block": bid, "order": "ABBA" if labels[0] == "control" else "BAAB", "candidate_minus_control_ms": delta}
            (blocks if kind == "measurement" else sham).append(item)
        summaries.append({"id": cid, "blocks": blocks, "calibration": sham,
                          "control_cpu_ms": statistics.median([r["owner_cpu_ms"] for r in steps if r.get("kind") == "measurement" and r.get("mode") == "control" and number(r.get("owner_cpu_ms"))]) if blocks else None,
                          "control_wall_ms": statistics.median([r["reload_wall_ms"] for r in steps if r.get("kind") == "measurement" and r.get("mode") == "control" and number(r.get("reload_wall_ms"))]) if blocks else None})
    if issues:
        return {"valid": False, "issues": issues, "decision": "invalid", "campaigns": summaries}
    reasons = []
    if len(summaries) < 2:
        reasons.append("Need independent JVM replication; one JVM is a screening hint")
    for summary in summaries:
        for kind, count in (("blocks", 2), ("calibration", 2)):
            if len(summary[kind]) < count or {b["order"] for b in summary[kind]} != {"ABBA", "BAAB"}:
                reasons.append(f"{summary['id']}: need both orders and at least two {kind}")
    for campaign in campaigns:
        observations = [row for row in campaign["steps"] if row["kind"] in ("measurement", "calibration")]
        if not observations or observations[0]["kind"] != "calibration" or observations[-1]["kind"] != "calibration":
            reasons.append(f"{campaign['id']}: A/A blocks must bracket candidate measurements")
    if reasons:
        return {"valid": True, "issues": [], "decision": "insufficient_design", "reasons": reasons, "campaigns": summaries}
    cpu_supported, wall_regression, wall_supported = [], [], []
    for summary in summaries:
        block = summary["blocks"]
        sham = summary["calibration"]
        cpu_noise = max(abs(b["candidate_minus_control_ms"]["owner_cpu_ms"]) for b in sham)
        wall_noise = max(abs(b["candidate_minus_control_ms"]["reload_wall_ms"]) for b in sham)
        cpu_threshold = max(contract["minimum_cpu_ms"], contract["minimum_cpu_fraction"] * summary["control_cpu_ms"], cpu_noise)
        wall_threshold = max(contract["wall_regression_ms"], contract["wall_regression_fraction"] * summary["control_wall_ms"], wall_noise)
        cpu = [b["candidate_minus_control_ms"]["owner_cpu_ms"] for b in block]
        wall = [b["candidate_minus_control_ms"]["reload_wall_ms"] for b in block]
        summary.update(cpu_noise_bound_ms=cpu_noise, wall_noise_bound_ms=wall_noise,
                       cpu_threshold_ms=cpu_threshold, wall_threshold_ms=wall_threshold,
                       cpu_delta_range_ms=[min(cpu), max(cpu)], wall_delta_range_ms=[min(wall), max(wall)])
        cpu_supported.append(all(d < -cpu_threshold for d in cpu))
        wall_regression.append(all(d > wall_threshold for d in wall))
        wall_supported.append(all(d < -wall_threshold for d in wall))
    decision = "reject_repeatable_wall_regression" if all(wall_regression) else "support_final_uninstrumented_gate" if all(cpu_supported) else "no_repeatable_cpu_win"
    return {"valid": True, "issues": [], "decision": decision,
            "wall_win_supported": all(wall_supported), "campaigns": summaries,
            "scope": "Warm full reload screening; not cold-start proof, significance or production promotion"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = analyze(json.loads(args.evidence.read_text(encoding="utf-8-sig")))
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        result = {"valid": False, "decision": "invalid", "issues": [str(error)]}
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["valid"] else 1)


if __name__ == "__main__":
    main()
