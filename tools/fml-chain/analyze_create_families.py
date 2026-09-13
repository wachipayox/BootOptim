#!/usr/bin/env python3
"""Analyze exact Create 6.0.10 registration-family wall nested in the observed FML DAG."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import analyze_fml_chain as fml

EXPECTED_CREATE_VERSION = "6.0.10"
EXPECTED_SOURCE_PIN = "ac0c444d9828da3453ae8cc65338e8de063286fb"
REQUIRED_EXECUTION_SUBCHAIN = ["colorwheel", "flywheel", "ponder", "create"]
REQUIRED_DIRECT_DEPENDENCY_CHAIN = ["colorwheel", "flywheel", "ponder", "create", "ratatouille"]

START_BOUNDARY = "com.simibubi.create.foundation.data.CreateRegistrate.registerEventListeners"
FAMILY_BOUNDARIES = [
    ("sound_events", "com.simibubi.create.AllSoundEvents.prepare"),
    ("creative_tabs", "com.simibubi.create.AllCreativeModeTabs.register"),
    ("armor_materials", "com.simibubi.create.content.equipment.armor.AllArmorMaterials.register"),
    ("display_sources", "com.simibubi.create.AllDisplaySources.register"),
    ("display_targets", "com.simibubi.create.AllDisplayTargets.register"),
    ("blocks", "com.simibubi.create.AllBlocks.register"),
    ("items", "com.simibubi.create.AllItems.register"),
    ("fluids", "com.simibubi.create.AllFluids.register"),
    ("palette_blocks", "com.simibubi.create.content.decoration.palettes.AllPaletteBlocks.register"),
    ("menus", "com.simibubi.create.AllMenuTypes.register"),
    ("entities", "com.simibubi.create.AllEntityTypes.register"),
    ("block_entities", "com.simibubi.create.AllBlockEntityTypes.register"),
    ("recipes", "com.simibubi.create.AllRecipeTypes.register"),
    ("particles", "com.simibubi.create.AllParticleTypes.register"),
    ("structure_processors", "com.simibubi.create.AllStructureProcessorTypes.register"),
    ("entity_data_serializers", "com.simibubi.create.AllEntityDataSerializers.register"),
    ("packets", "com.simibubi.create.AllPackets.register"),
    ("worldgen_features", "com.simibubi.create.infrastructure.worldgen.AllFeatures.register"),
    ("worldgen_placement_modifiers", "com.simibubi.create.infrastructure.worldgen.AllPlacementModifiers.register"),
    ("ingredients", "com.simibubi.create.foundation.recipe.AllIngredients.register"),
    ("attachments", "com.simibubi.create.AllAttachmentTypes.register"),
    ("data_components", "com.simibubi.create.AllDataComponents.register"),
    ("map_decorations", "com.simibubi.create.AllMapDecorationTypes.register"),
    ("mounted_storage_types", "com.simibubi.create.AllMountedStorageTypes.register"),
]
TAIL_BOUNDARIES = [
    "com.simibubi.create.infrastructure.config.AllConfigs.register",
    "com.simibubi.create.AllSchematicStateFilters.registerDefaults",
    "com.simibubi.create.AllBogeyStyles.init",
    "net.neoforged.neoforge.common.NeoForgeMod.enableMilkFluid",
]
EXPECTED_BOUNDARIES = [START_BOUNDARY, *[origin for _, origin in FAMILY_BOUNDARIES], *TAIL_BOUNDARIES]


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


def direct_dependencies(events: list[dict[str, Any]]) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for event in events:
        if event.get("kind") != "dependencies" or not event.get("mod"):
            continue
        result[event["mod"]] = [value for value in (event.get("detail") or "").split(",") if value]
    return result


def analyze(events: list[dict[str, Any]]) -> dict[str, Any]:
    fml_result = fml.analyze(events)

    # The sink can vary between exact-pack runs because unrelated worker serialization after Create can
    # change which dependent finishes last. What must remain causal is (a) the non-overlapping observed
    # execution chain into Create and (b) the direct dependency edges out through ratatouille.
    execution_chain = fml_result["observed_execution_critical_chain_mods"]
    if not contains_subchain(execution_chain, REQUIRED_EXECUTION_SUBCHAIN):
        raise SystemExit("expected execution subchain into Create not observed: " + " -> ".join(REQUIRED_EXECUTION_SUBCHAIN))
    deps = direct_dependencies(events)
    missing_edges = [
        f"{parent}->{child}"
        for parent, child in zip(REQUIRED_DIRECT_DEPENDENCY_CHAIN, REQUIRED_DIRECT_DEPENDENCY_CHAIN[1:])
        if parent not in deps.get(child, [])
    ]
    if missing_edges:
        raise SystemExit("expected direct dependency edges not observed: " + ", ".join(missing_edges))

    disabled = [event for event in events if event.get("kind") == "create_profile_disabled"]
    if disabled:
        raise SystemExit(f"Create profile fail-closed: {disabled[-1].get('detail')}")

    header = exactly_one(events, "create_profile_header")
    fields = detail_fields(header.get("detail"))
    if fields.get("expected_create") != EXPECTED_CREATE_VERSION or fields.get("observed_create") != EXPECTED_CREATE_VERSION:
        raise SystemExit(f"Create runtime version mismatch: {fields}")
    if fields.get("observed_mod") != "create" or fields.get("source_pin") != EXPECTED_SOURCE_PIN:
        raise SystemExit(f"Create identity/source mismatch: {fields}")

    begin = exactly_one(events, "create_ctor_begin")
    end = exactly_one(events, "create_ctor_end")
    tid = begin.get("tid")
    if not isinstance(tid, int) or end.get("tid") != tid:
        raise SystemExit("Create onCtor crossed threads or lacks immutable tid")
    if end.get("detail"):
        raise SystemExit(f"Create onCtor threw: {end.get('detail')}")

    create_construct_begin = [e for e in events if e.get("kind") == "construct_begin" and e.get("mod") == "create"]
    create_construct_end = [e for e in events if e.get("kind") == "construct_end" and e.get("mod") == "create"]
    if len(create_construct_begin) != 1 or len(create_construct_end) != 1:
        raise SystemExit("expected exactly one outer FML construct interval for create")
    if not (create_construct_begin[0]["ns"] <= begin["ns"] <= end["ns"] <= create_construct_end[0]["ns"]):
        raise SystemExit("Create onCtor is not nested inside the FML create construct interval")
    if create_construct_begin[0].get("tid") != tid:
        raise SystemExit("Create onCtor thread differs from outer FML create node")

    boundaries = [event for event in events if event.get("kind") == "create_boundary"]
    boundaries.sort(key=lambda event: (event["ns"], event.get("seq", 0)))
    if len(boundaries) != len(EXPECTED_BOUNDARIES):
        raise SystemExit(f"expected {len(EXPECTED_BOUNDARIES)} Create boundaries, found {len(boundaries)}")

    observed = []
    for event in boundaries:
        if event.get("tid") != tid:
            raise SystemExit("Create boundary crossed threads")
        detail = detail_fields(event.get("detail"))
        if "throw" in detail:
            raise SystemExit(f"Create boundary threw: {detail}")
        observed.append(detail.get("origin"))
    if observed != EXPECTED_BOUNDARIES:
        raise SystemExit(f"Create boundary topology mismatch: {observed}")

    points = [begin["ns"], *[event["ns"] for event in boundaries], end["ns"]]
    if any(right < left for left, right in zip(points, points[1:])):
        raise SystemExit("Create boundaries are not monotonic")

    family_start_ns = boundaries[0]["ns"]
    family_rows = []
    previous_ns = family_start_ns
    for index, (name, origin) in enumerate(FAMILY_BOUNDARIES, start=1):
        current_ns = boundaries[index]["ns"]
        family_rows.append({
            "name": name,
            "origin": origin,
            "start_ns": previous_ns,
            "end_ns": current_ns,
            "wall_ms": (current_ns - previous_ns) / 1_000_000.0,
        })
        previous_ns = current_ns

    registration_families_ms = (previous_ns - family_start_ns) / 1_000_000.0
    family_sum_ms = sum(row["wall_ms"] for row in family_rows)
    family_tiling_error_ms = abs(registration_families_ms - family_sum_ms)
    if family_tiling_error_ms > 0.001:
        raise SystemExit(f"registration-family partition does not tile: {family_tiling_error_ms:.6f} ms")

    ctor_ms = (end["ns"] - begin["ns"]) / 1_000_000.0
    create_row = next((row for row in fml_result["top_nodes"] if row["mod"] == "create"), None)
    if create_row is None:
        raise SystemExit("Create is missing from FML top-node output")
    largest = max(family_rows, key=lambda row: row["wall_ms"])

    return {
        "fml": fml_result,
        "create_profile_header": header.get("detail"),
        "create_thread_id": tid,
        "create_on_ctor_ms": ctor_ms,
        "create_outer_constructor_exclusive_ms": create_row.get("constructor_exclusive_ms"),
        "create_outer_node_ms": create_row.get("node_ms"),
        "registration_families_ms": registration_families_ms,
        "registration_families_pct_of_ctor": 100.0 * registration_families_ms / ctor_ms,
        "family_tiling_error_ms": family_tiling_error_ms,
        "families": family_rows,
        "largest_family": largest,
        "execution_subchain_verified": REQUIRED_EXECUTION_SUBCHAIN,
        "direct_dependency_chain_verified": REQUIRED_DIRECT_DEPENDENCY_CHAIN,
        "interpretation": {
            "timing_kind": "non-overlapping serial wall intervals between exact stock Create 6.0.10 call returns; each interval includes target class initialization before its return",
            "savings_claim": False,
            "a_b": False,
            "production_change": False,
        },
    }


def markdown(result: dict[str, Any]) -> str:
    fml_result = result["fml"]
    lines = [
        "# Create 6.0.10 registration-family profile nested in FML DAG",
        "",
        f"- FML construction gate: **{fml_result['gate_ms']:.3f} ms**",
        "- observed non-overlapping execution subchain into Create: " + " -> ".join(f"`{mod}`" for mod in result["execution_subchain_verified"]),
        "- direct dependency chain through downstream Ratatouille: " + " -> ".join(f"`{mod}`" for mod in result["direct_dependency_chain_verified"]),
        f"- Create outer node: **{result['create_outer_node_ms']:.3f} ms**",
        f"- Create outer constructor-exclusive: **{result['create_outer_constructor_exclusive_ms']:.3f} ms**",
        f"- exact `Create.onCtor`: **{result['create_on_ctor_ms']:.3f} ms**",
        f"- exact registration-family slice: **{result['registration_families_ms']:.3f} ms** ({result['registration_families_pct_of_ctor']:.2f}% of onCtor)",
        "",
        "Rows are a contiguous, non-overlapping serial wall partition from the return of `CreateRegistrate.registerEventListeners` through the return of `AllMountedStorageTypes.register`. They are attribution, not savings. Because many Create `register()` methods exist only to force class loading, each interval deliberately includes any target `<clinit>` work that occurs before the method returns.",
        "",
        "| stock family/call | wall ms |",
        "| --- | ---: |",
    ]
    for row in result["families"]:
        lines.append(f"| `{row['name']}` | {row['wall_ms']:.3f} |")
    lines.extend([
        "",
        "## Causal placement",
        "",
        "The execution-chain check is intentionally anchored at Create rather than the run's final FML sink: unrelated work on downstream executor workers can change which dependent finishes last without changing Create's causal criticality. Direct dependency edges separately prove `create -> ratatouille` for the exact pack.",
        "",
        "Inside the `create` node the measured family rows execute in the exact stock source order on one immutable worker tid. No callbacks, class initialization, registration calls, failures, or threads are moved.",
        "",
        f"Largest family interval: **`{result['largest_family']['name']}` = {result['largest_family']['wall_ms']:.3f} ms**. This is an investigation target only.",
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
