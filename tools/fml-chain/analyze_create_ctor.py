#!/usr/bin/env python3
"""Analyze Create 6.0.10 onCtor coarse phases nested in the FML observed critical chain."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import analyze_fml_chain as fml

EXPECTED_CREATE_VERSION = "6.0.10"
EXPECTED_SOURCE_PIN = "ac0c444d9828da3453ae8cc65338e8de063286fb"
EXPECTED_BOUNDARIES = [
    "com.simibubi.create.foundation.data.CreateRegistrate.registerEventListeners",
    "com.simibubi.create.AllMountedStorageTypes.register",
    "com.simibubi.create.infrastructure.config.AllConfigs.register",
    "com.simibubi.create.AllSchematicStateFilters.registerDefaults",
    "com.simibubi.create.AllBogeyStyles.init",
    "net.neoforged.neoforge.common.NeoForgeMod.enableMilkFluid",
]
PHASE_NAMES = [
    "setup_and_registrate",
    "registration_families",
    "config_registration",
    "package_and_schematic_defaults",
    "bogey_non_thread_safe",
    "compat_and_milk",
    "listener_and_optional_compat_tail",
]
REQUIRED_DEPENDENCY_SUBCHAIN = ["colorwheel", "flywheel", "ponder", "create", "ratatouille"]


def detail_fields(detail: str | None) -> dict[str, str]:
    out: dict[str, str] = {}
    for part in (detail or "").split(";"):
        if not part:
            continue
        key, sep, value = part.partition("=")
        if sep:
            out[key] = value
    return out


def exactly_one(events: list[dict[str, Any]], kind: str) -> dict[str, Any]:
    matched = [event for event in events if event.get("kind") == kind]
    if len(matched) != 1:
        raise SystemExit(f"expected exactly one {kind}, found {len(matched)}")
    return matched[0]


def contains_subchain(chain: list[str], expected: list[str]) -> bool:
    width = len(expected)
    return any(chain[i:i + width] == expected for i in range(len(chain) - width + 1))


def analyze(events: list[dict[str, Any]]) -> dict[str, Any]:
    fml_result = fml.analyze(events)
    chain = fml_result["dependency_last_predecessor_chain_mods"]
    if not contains_subchain(chain, REQUIRED_DEPENDENCY_SUBCHAIN):
        raise SystemExit(
            "expected dependency subchain not observed: "
            + " -> ".join(REQUIRED_DEPENDENCY_SUBCHAIN)
            + "; got " + " -> ".join(chain)
        )

    disabled = [event for event in events if event.get("kind") == "create_profile_disabled"]
    if disabled:
        raise SystemExit(f"Create profile fail-closed: {disabled[-1].get('detail')}")

    header = exactly_one(events, "create_profile_header")
    header_fields = detail_fields(header.get("detail"))
    if header_fields.get("expected_create") != EXPECTED_CREATE_VERSION:
        raise SystemExit(f"unexpected expected Create version: {header_fields}")
    if header_fields.get("observed_create") != EXPECTED_CREATE_VERSION:
        raise SystemExit(f"Create runtime version mismatch: {header_fields}")
    if header_fields.get("observed_mod") != "create":
        raise SystemExit(f"Create mod identity mismatch: {header_fields}")
    if header_fields.get("source_pin") != EXPECTED_SOURCE_PIN:
        raise SystemExit(f"Create source pin mismatch: {header_fields}")

    begin = exactly_one(events, "create_ctor_begin")
    end = exactly_one(events, "create_ctor_end")
    if begin.get("tid") != end.get("tid") or not isinstance(begin.get("tid"), int):
        raise SystemExit("Create onCtor crossed threads or lacks immutable thread id")
    if end["ns"] < begin["ns"]:
        raise SystemExit("Create onCtor end precedes begin")
    if end.get("detail"):
        raise SystemExit(f"Create onCtor threw: {end.get('detail')}")

    create_construct_begin = [e for e in events if e.get("kind") == "construct_begin" and e.get("mod") == "create"]
    create_construct_end = [e for e in events if e.get("kind") == "construct_end" and e.get("mod") == "create"]
    if len(create_construct_begin) != 1 or len(create_construct_end) != 1:
        raise SystemExit("expected exactly one outer FML construct interval for create")
    if not (create_construct_begin[0]["ns"] <= begin["ns"] <= end["ns"] <= create_construct_end[0]["ns"]):
        raise SystemExit("Create onCtor is not nested inside the FML create construct interval")
    if begin.get("tid") != create_construct_begin[0].get("tid"):
        raise SystemExit("Create onCtor thread differs from outer FML create node")

    boundaries = [event for event in events if event.get("kind") == "create_boundary"]
    if len(boundaries) != len(EXPECTED_BOUNDARIES):
        raise SystemExit(f"expected {len(EXPECTED_BOUNDARIES)} Create boundaries, found {len(boundaries)}")
    boundaries.sort(key=lambda event: (event["ns"], event.get("seq", 0)))
    observed_origins = []
    for event in boundaries:
        if event.get("tid") != begin.get("tid"):
            raise SystemExit("Create boundary crossed threads")
        fields = detail_fields(event.get("detail"))
        if "throw" in fields:
            raise SystemExit(f"Create boundary threw: {fields}")
        observed_origins.append(fields.get("origin"))
    if observed_origins != EXPECTED_BOUNDARIES:
        raise SystemExit(f"Create boundary topology mismatch: {observed_origins}")

    points = [begin["ns"], *[event["ns"] for event in boundaries], end["ns"]]
    if any(right < left for left, right in zip(points, points[1:])):
        raise SystemExit("Create boundaries are not monotonic")

    phases = []
    for index, name in enumerate(PHASE_NAMES):
        start_ns, end_ns = points[index], points[index + 1]
        phases.append({
            "name": name,
            "start_ns": start_ns,
            "end_ns": end_ns,
            "wall_ms": (end_ns - start_ns) / 1_000_000.0,
        })

    ctor_ms = (end["ns"] - begin["ns"]) / 1_000_000.0
    phase_sum_ms = sum(phase["wall_ms"] for phase in phases)
    tiling_error_ms = abs(ctor_ms - phase_sum_ms)
    if tiling_error_ms > 0.001:
        raise SystemExit(f"Create phase partition does not tile onCtor: error={tiling_error_ms:.6f} ms")

    create_row = next(
        (row for row in fml_result["top_nodes"] if row["mod"] == "create"),
        None,
    )
    if create_row is None:
        raise SystemExit("Create is missing from FML top-node output")

    largest = max(phases, key=lambda phase: phase["wall_ms"])
    return {
        "fml": fml_result,
        "create_profile_header": header.get("detail"),
        "create_thread_id": begin.get("tid"),
        "create_on_ctor_ms": ctor_ms,
        "create_outer_constructor_exclusive_ms": create_row.get("constructor_exclusive_ms"),
        "create_outer_node_ms": create_row.get("node_ms"),
        "phase_tiling_error_ms": tiling_error_ms,
        "phases": phases,
        "largest_phase": largest,
        "dependency_subchain_verified": REQUIRED_DEPENDENCY_SUBCHAIN,
        "interpretation": {
            "timing_kind": "inclusive serial wall partition inside stock Create.onCtor",
            "savings_claim": False,
            "a_b": False,
            "bogey_phase_thread_safe": False,
            "tail_purity_proven": False,
        },
    }


def markdown(result: dict[str, Any]) -> str:
    fml_result = result["fml"]
    lines = [
        "# Create 6.0.10 constructor profile nested in FML DAG",
        "",
        f"- FML gate: **{fml_result['gate_ms']:.3f} ms**",
        "- dependency lineage: " + " -> ".join(f"`{mod}`" for mod in fml_result["dependency_last_predecessor_chain_mods"]),
        f"- Create outer node: **{result['create_outer_node_ms']:.3f} ms**",
        f"- Create outer constructor-exclusive: **{result['create_outer_constructor_exclusive_ms']:.3f} ms**",
        f"- exact `Create.onCtor`: **{result['create_on_ctor_ms']:.3f} ms**",
        f"- Create version/source gate: `{result['create_profile_header']}`",
        "",
        "The rows below are a contiguous serial partition of stock `Create.onCtor`; they are not task sums and are not a savings estimate.",
        "",
        "| phase | wall ms | semantic boundary |",
        "| --- | ---: | --- |",
    ]
    descriptions = {
        "setup_and_registrate": "entry -> CreateRegistrate listener registration complete",
        "registration_families": "-> AllMountedStorageTypes.register complete",
        "config_registration": "-> AllConfigs.register complete",
        "package_and_schematic_defaults": "-> AllSchematicStateFilters.registerDefaults complete",
        "bogey_non_thread_safe": "-> AllBogeyStyles.init complete; upstream non-thread-safe region kept intact",
        "compat_and_milk": "-> NeoForgeMod.enableMilkFluid complete",
        "listener_and_optional_compat_tail": "-> onCtor return; event listeners + optional compat retain stock order",
    }
    for phase in result["phases"]:
        lines.append(f"| `{phase['name']}` | {phase['wall_ms']:.3f} | {descriptions[phase['name']]} |")
    lines.extend([
        "",
        "## Causal DAG",
        "",
        "`colorwheel -> flywheel -> ponder -> create`",
        "",
        "Inside `create` (strict serial stock order):",
        "",
        "`setup_and_registrate -> registration_families -> config_registration -> package_and_schematic_defaults -> bogey_non_thread_safe -> compat_and_milk -> listener_and_optional_compat_tail`",
        "",
        "`create -> ratatouille` (dependency lineage; the FML analyzer retains worker-serialization blockers separately).",
        "",
        f"Largest measured internal phase: **`{result['largest_phase']['name']}` = {result['largest_phase']['wall_ms']:.3f} ms**. This is attribution only.",
    ])
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    args = parser.parse_args()

    result = analyze(fml.load_events(args.input))
    args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown_output.write_text(markdown(result), encoding="utf-8")


if __name__ == "__main__":
    main()
