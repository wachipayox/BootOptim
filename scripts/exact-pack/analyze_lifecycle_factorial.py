#!/usr/bin/env python3
"""Validate and summarize a contract-safe lifecycle/loader complement factorial.

The analyzer intentionally refuses TTMM, reload, ModelBakery/model and post-reload
metrics.  It is for broad pre-reload loader/lifecycle attribution only.  A positive
interaction term is a reason to trace that family more deeply, never a savings claim.
"""

from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

FORBIDDEN_METRIC_TOKENS = ("main_menu", "startup", "post_", "reload", "model", "bake", "atlas")


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_result(result: dict, spec: dict, slot: str, metric: str) -> float:
    if any(token in metric.lower() for token in FORBIDDEN_METRIC_TOKENS):
        raise ValueError(f"metric is outside lifecycle/loader scope: {metric}")
    manifest = result.get("scaling_variant_manifest") or {}
    expected_variant = spec["variants"][slot]
    if manifest.get("variant_id") != expected_variant:
        raise ValueError(f"{slot}: expected variant {expected_variant!r}, got {manifest.get('variant_id')!r}")
    if manifest.get("source_pack_fingerprint") != spec["fixture"]["source_mod_fingerprint"]:
        raise ValueError(f"{slot}: source pack fingerprint mismatch")
    if result.get("resource_contract_valid") is not True:
        raise ValueError(f"{slot}: resource contract is not valid")
    if result.get("diagnostic_only") is not False:
        raise ValueError(f"{slot}: diagnostic-only endpoint is not comparable")
    if result.get("bootoptim_mixin_errors") != 0:
        raise ValueError(f"{slot}: BootOptim/Mixin errors present")
    if manifest.get("missing_dependencies"):
        raise ValueError(f"{slot}: manifest has missing dependencies")
    if not isinstance(result.get("main_menu_ms"), (int, float)):
        raise ValueError(f"{slot}: main_menu endpoint missing")
    value = result.get(metric)
    if not isinstance(value, (int, float)):
        raise ValueError(f"{slot}: metric {metric!r} missing")
    return float(value)


def summarize(values: list[float]) -> dict:
    return {
        "n": len(values),
        "median_ms": statistics.median(values),
        "min_ms": min(values),
        "max_ms": max(values),
        "range_ms": max(values) - min(values),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--metric", default="mod_entrypoint_ms")
    for slot in ("full", "remove_a", "remove_b", "remove_ab"):
        parser.add_argument(f"--{slot.replace('_', '-')}", type=Path, action="append", required=True)
    args = parser.parse_args()

    spec = load(args.spec)
    if spec.get("schema") != 1:
        raise SystemExit("unsupported campaign schema")
    metric = args.metric
    if metric not in spec["measurement_contract"]["allowed_metrics"]:
        raise SystemExit(f"metric {metric!r} is not allowed by campaign contract")

    slots: dict[str, list[float]] = {}
    for slot in ("full", "remove_a", "remove_b", "remove_ab"):
        paths = getattr(args, slot)
        values = [validate_result(load(path), spec, slot, metric) for path in paths]
        minimum = int(spec["measurement_contract"].get("minimum_fresh_vm_repetitions", 1))
        if len(values) < minimum:
            raise SystemExit(f"{slot}: need at least {minimum} fresh-VM repetitions, got {len(values)}")
        slots[slot] = values

    medians = {slot: statistics.median(values) for slot, values in slots.items()}
    interaction_ms = medians["full"] - medians["remove_a"] - medians["remove_b"] + medians["remove_ab"]
    output = {
        "schema": 1,
        "campaign_id": spec["campaign_id"],
        "planner_version": spec["planner_version"],
        "origin": spec["measurement_contract"]["origin"],
        "start_marker": spec["measurement_contract"]["start_marker"],
        "endpoint": spec["measurement_contract"]["endpoint"],
        "metric": metric,
        "families": spec["families"],
        "variants": {slot: summarize(values) for slot, values in slots.items()},
        "factorial_interaction_ms": interaction_ms,
        "interpretation": (
            "positive means the A+B retained workload has super-additive pre-reload loader/lifecycle cost; "
            "it is an attribution trigger, not TTMM savings"
        ),
    }
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
