#!/usr/bin/env python3
"""Validate and summarize a contract-safe lifecycle/loader complement factorial.

The analyzer intentionally refuses TTMM, reload, ModelBakery/model and post-reload
metrics. It is for broad pre-reload loader/lifecycle attribution only. A positive
interaction term is a reason to trace that family more deeply, never a savings claim.

A numerical 2x2 interaction is only emitted when the materialized closures are also
structurally factorial: removing both factors may remove more work, but it must never
re-add an artifact, and neither single-factor removal may subsume the other factor's
entire removal closure. Otherwise there is no independent crossing to interpret.
"""

from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

FORBIDDEN_METRIC_TOKENS = ("main_menu", "startup", "post_", "reload", "model", "bake", "atlas")
SLOTS = ("full", "remove_a", "remove_b", "remove_ab")


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_result(result: dict, spec: dict, slot: str, metric: str) -> tuple[str, float, frozenset[str]]:
    if any(token in metric.lower() for token in FORBIDDEN_METRIC_TOKENS):
        raise ValueError(f"metric is outside lifecycle/loader scope: {metric}")
    manifest = result.get("scaling_variant_manifest") or {}
    expected = spec["variants"][slot]
    expected_variants = [expected] if isinstance(expected, str) else list(expected)
    variant_id = manifest.get("variant_id")
    if variant_id not in expected_variants:
        raise ValueError(f"{slot}: unexpected variant {variant_id!r}; expected one of {expected_variants!r}")
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
    selected = manifest.get("selected_artifacts")
    if not isinstance(selected, list) or not selected or not all(isinstance(item, str) for item in selected):
        raise ValueError(f"{slot}: selected_artifacts missing from scaling manifest")
    return variant_id, float(value), frozenset(selected)


def summarize(values: list[float]) -> dict:
    return {
        "n": len(values),
        "median_ms": statistics.median(values),
        "min_ms": min(values),
        "max_ms": max(values),
        "range_ms": max(values) - min(values),
    }


def validate_factorial_structure(selections: dict[str, frozenset[str]]) -> dict:
    full = selections["full"]
    remove_a = selections["remove_a"]
    remove_b = selections["remove_b"]
    remove_ab = selections["remove_ab"]

    for slot, selected in (("remove_a", remove_a), ("remove_b", remove_b), ("remove_ab", remove_ab)):
        added = selected - full
        if added:
            raise SystemExit(
                f"factorial structure invalid: {slot} contains {len(added)} artifact(s) absent from full; "
                f"sample={sorted(added)[:5]!r}"
            )

    monotone_extra = remove_ab - (remove_a & remove_b)
    if monotone_extra:
        raise SystemExit(
            "factorial structure invalid: remove_ab re-adds artifact(s) relative to a single-factor cell; "
            f"count={len(monotone_extra)} sample={sorted(monotone_extra)[:5]!r}"
        )

    removed_a = full - remove_a
    removed_b = full - remove_b
    if not removed_a or not removed_b:
        raise SystemExit("factorial structure invalid: each factor must remove at least one artifact")
    if removed_a <= removed_b:
        raise SystemExit(
            "factorial structure invalid: factor B removal subsumes the entire factor A removal closure; "
            f"removed_a={len(removed_a)} removed_b={len(removed_b)}"
        )
    if removed_b <= removed_a:
        raise SystemExit(
            "factorial structure invalid: factor A removal subsumes the entire factor B removal closure; "
            f"removed_a={len(removed_a)} removed_b={len(removed_b)}"
        )

    return {
        "full_artifacts": len(full),
        "remove_a_artifacts": len(remove_a),
        "remove_b_artifacts": len(remove_b),
        "remove_ab_artifacts": len(remove_ab),
        "removed_a_artifacts": len(removed_a),
        "removed_b_artifacts": len(removed_b),
        "removed_by_both_artifacts": len(removed_a & removed_b),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--metric", default="mod_entrypoint_ms")
    for slot in SLOTS:
        parser.add_argument(f"--{slot.replace('_', '-')}", type=Path, action="append", required=True)
    args = parser.parse_args()

    spec = load(args.spec)
    if spec.get("schema") != 1:
        raise SystemExit("unsupported campaign schema")
    metric = args.metric
    if metric not in spec["measurement_contract"]["allowed_metrics"]:
        raise SystemExit(f"metric {metric!r} is not allowed by campaign contract")

    slots: dict[str, list[float]] = {}
    selections: dict[str, frozenset[str]] = {}
    for slot in SLOTS:
        paths = getattr(args, slot)
        validated = [validate_result(load(path), spec, slot, metric) for path in paths]
        ids = [item[0] for item in validated]
        values = [item[1] for item in validated]
        selected_sets = [item[2] for item in validated]
        minimum = int(spec["measurement_contract"].get("minimum_fresh_vm_repetitions", 1))
        expected = spec["variants"][slot]
        expected_ids = [expected] if isinstance(expected, str) else list(expected)
        if len(values) < minimum:
            raise SystemExit(f"{slot}: need at least {minimum} fresh-VM repetitions, got {len(values)}")
        if len(expected_ids) > 1 and sorted(ids) != sorted(expected_ids):
            raise SystemExit(f"{slot}: alias set mismatch; got {sorted(ids)!r}, expected {sorted(expected_ids)!r}")
        if any(selected != selected_sets[0] for selected in selected_sets[1:]):
            raise SystemExit(f"{slot}: fresh-VM aliases did not materialize the same artifact closure")
        slots[slot] = values
        selections[slot] = selected_sets[0]

    structure = validate_factorial_structure(selections)
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
        "factorial_structure": structure,
        "variants": {slot: summarize(values) for slot, values in slots.items()},
        "factorial_interaction_ms": interaction_ms,
        "interpretation": (
            "positive means the independently crossed A+B retained workload has super-additive pre-reload "
            "loader/lifecycle cost; it is an attribution trigger, not TTMM savings"
        ),
    }
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
