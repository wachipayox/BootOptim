#!/usr/bin/env python3
"""Analyze Create 6.0.10 AllBlockEntityTypes.<clinit> as serial entry-to-entry wall."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import analyze_create_families as families
import analyze_fml_chain as fml

EXPECTED_CREATE_VERSION = "6.0.10"
EXPECTED_SOURCE_PIN = "ac0c444d9828da3453ae8cc65338e8de063286fb"
EXPECTED_FIRST_ENTRY = "schematicannon"
EXPECTED_LAST_ENTRY = "clipboard"
MIN_EXPECTED_ENTRY_COUNT = 80
EXPECTED_BUILDER = "com.simibubi.create.foundation.data.CreateBlockEntityBuilder"


def detail_fields(detail: str | None) -> dict[str, str]:
    return families.detail_fields(detail)


def exactly_one(events: list[dict[str, Any]], kind: str) -> dict[str, Any]:
    return families.exactly_one(events, kind)


def analyze(events: list[dict[str, Any]]) -> dict[str, Any]:
    family_result = families.analyze(events)
    header = detail_fields(family_result["create_profile_header"])
    if header.get("observed_create") != EXPECTED_CREATE_VERSION or header.get("source_pin") != EXPECTED_SOURCE_PIN:
        raise SystemExit(f"Create identity/source mismatch: {header}")

    reentry = [event for event in events if event.get("kind") == "create_be_clinit_reentry"]
    if reentry:
        raise SystemExit(f"AllBlockEntityTypes.<clinit> re-entered unexpectedly: {len(reentry)}")

    begin = exactly_one(events, "create_be_clinit_begin")
    end = exactly_one(events, "create_be_clinit_end")
    tid = family_result["create_thread_id"]
    if begin.get("tid") != tid or end.get("tid") != tid:
        raise SystemExit("AllBlockEntityTypes.<clinit> thread differs from Create.onCtor")
    if end.get("detail"):
        raise SystemExit(f"AllBlockEntityTypes.<clinit> threw: {end.get('detail')}")
    if end["ns"] < begin["ns"]:
        raise SystemExit("AllBlockEntityTypes.<clinit> end precedes begin")

    block_family = next((row for row in family_result["families"] if row["name"] == "block_entities"), None)
    if block_family is None:
        raise SystemExit("block_entities family row missing")
    if not (block_family["start_ns"] <= begin["ns"] <= end["ns"] <= block_family["end_ns"]):
        raise SystemExit("AllBlockEntityTypes.<clinit> is not nested inside the serial block_entities family")

    entries = [event for event in events if event.get("kind") == "create_be_entry_boundary"]
    entries.sort(key=lambda event: (event["ns"], event.get("seq", 0)))
    if len(entries) < MIN_EXPECTED_ENTRY_COUNT:
        raise SystemExit(f"too few block-entity entry boundaries: {len(entries)} < {MIN_EXPECTED_ENTRY_COUNT}")

    observed_names: list[str] = []
    entry_rows: list[dict[str, Any]] = []
    previous_ns = begin["ns"]
    seen: set[str] = set()
    for index, event in enumerate(entries):
        if event.get("tid") != tid:
            raise SystemExit("block-entity entry boundary crossed threads")
        if not (begin["ns"] <= event["ns"] <= end["ns"]):
            raise SystemExit("block-entity entry boundary escaped AllBlockEntityTypes.<clinit>")
        detail = detail_fields(event.get("detail"))
        if "throw" in detail:
            raise SystemExit(f"block-entity builder/register threw: {detail}")
        name = detail.get("name")
        if not name or name == "null":
            raise SystemExit(f"block-entity boundary lacks name: {detail}")
        if name in seen:
            raise SystemExit(f"duplicate block-entity entry boundary: {name}")
        seen.add(name)
        if detail.get("owner_mod") != "create":
            raise SystemExit(f"block-entity entry owner mismatch: {detail}")
        if "block_entity_type" not in detail.get("registry", ""):
            raise SystemExit(f"block-entity registry mismatch: {detail}")
        if detail.get("builder") != EXPECTED_BUILDER:
            raise SystemExit(f"unexpected builder implementation: {detail}")
        if event["ns"] < previous_ns:
            raise SystemExit("block-entity boundaries are not monotonic")
        wall_ms = (event["ns"] - previous_ns) / 1_000_000.0
        entry_rows.append({
            "index": index,
            "name": name,
            "start_ns": previous_ns,
            "end_ns": event["ns"],
            "wall_ms": wall_ms,
        })
        observed_names.append(name)
        previous_ns = event["ns"]

    if observed_names[0] != EXPECTED_FIRST_ENTRY or observed_names[-1] != EXPECTED_LAST_ENTRY:
        raise SystemExit(
            f"block-entity source endpoints mismatch: first={observed_names[0]} last={observed_names[-1]}")

    tail_ms = (end["ns"] - previous_ns) / 1_000_000.0
    if tail_ms < 0:
        raise SystemExit("negative AllBlockEntityTypes.<clinit> tail")
    clinit_ms = (end["ns"] - begin["ns"]) / 1_000_000.0
    entries_ms = sum(row["wall_ms"] for row in entry_rows)
    tiling_error_ms = abs(clinit_ms - entries_ms - tail_ms)
    if tiling_error_ms > 0.001:
        raise SystemExit(f"block-entity entry partition does not tile <clinit>: {tiling_error_ms:.6f} ms")

    before_clinit_ms = (begin["ns"] - block_family["start_ns"]) / 1_000_000.0
    after_clinit_ms = (block_family["end_ns"] - end["ns"]) / 1_000_000.0
    if before_clinit_ms < 0 or after_clinit_ms < 0:
        raise SystemExit("negative block_entities family residual around <clinit>")

    block_family_ms = block_family["wall_ms"]
    family_accounted_error_ms = abs(block_family_ms - before_clinit_ms - clinit_ms - after_clinit_ms)
    if family_accounted_error_ms > 0.001:
        raise SystemExit(f"block_entities family does not tile around <clinit>: {family_accounted_error_ms:.6f} ms")

    ranked = sorted(entry_rows, key=lambda row: row["wall_ms"], reverse=True)
    top10_ms = sum(row["wall_ms"] for row in ranked[:10])
    fml_result = family_result["fml"]
    return {
        "fml": fml_result,
        "family": family_result,
        "entry_count": len(entry_rows),
        "first_entry": observed_names[0],
        "last_entry": observed_names[-1],
        "all_block_entity_types_clinit_ms": clinit_ms,
        "block_entities_family_ms": block_family_ms,
        "clinit_pct_of_block_entities_family": 100.0 * clinit_ms / block_family_ms,
        "before_clinit_ms": before_clinit_ms,
        "after_clinit_ms": after_clinit_ms,
        "clinit_tail_ms": tail_ms,
        "entry_tiling_error_ms": tiling_error_ms,
        "family_tiling_error_ms": family_accounted_error_ms,
        "entries": entry_rows,
        "top_entries": ranked[:20],
        "top10_ms": top10_ms,
        "top10_pct_of_clinit": 100.0 * top10_ms / clinit_ms,
        "interpretation": {
            "timing_kind": "non-overlapping serial wall from AllBlockEntityTypes.<clinit> entry to consecutive BlockEntityBuilder.register returns; each row includes builder configuration and transitive class initialization before that return",
            "observer_cost_included": True,
            "savings_claim": False,
            "a_b": False,
            "production_change": False,
        },
    }


def markdown(result: dict[str, Any]) -> str:
    fml_result = result["fml"]
    fam = result["family"]
    lines = [
        "# Create 6.0.10 AllBlockEntityTypes entry profile",
        "",
        f"- FML construction gate: **{fml_result['gate_ms']:.3f} ms**",
        f"- Create outer node: **{fam['create_outer_node_ms']:.3f} ms**",
        f"- exact `Create.onCtor`: **{fam['create_on_ctor_ms']:.3f} ms**",
        f"- registration-family slice: **{fam['registration_families_ms']:.3f} ms**",
        f"- `block_entities` serial family: **{result['block_entities_family_ms']:.3f} ms**",
        f"- exact `AllBlockEntityTypes.<clinit>`: **{result['all_block_entity_types_clinit_ms']:.3f} ms** ({result['clinit_pct_of_block_entities_family']:.2f}% of family)",
        f"- observed entry commits: **{result['entry_count']}**, source endpoints `{result['first_entry']}` -> `{result['last_entry']}`",
        f"- family residual before/after `<clinit>`: **{result['before_clinit_ms']:.3f} / {result['after_clinit_ms']:.3f} ms**",
        "",
        "Rows are contiguous non-overlapping serial wall intervals. A row ends when the corresponding `CreateBlockEntityBuilder` has returned through `BlockEntityBuilder.register()`. The interval therefore includes construction/configuration of that entry and any transitive class initialization since the preceding entry. Observer reflection/logging overhead is included; these are attribution measurements, not savings.",
        "",
        "| rank | source entry | serial wall ms |",
        "| ---: | --- | ---: |",
    ]
    for rank, row in enumerate(result["top_entries"], start=1):
        lines.append(f"| {rank} | `{row['name']}` | {row['wall_ms']:.3f} |")
    lines.extend([
        "",
        f"Top 10 entry intervals account for **{result['top10_ms']:.3f} ms** ({result['top10_pct_of_clinit']:.2f}% of `<clinit>`).",
        "",
        "## Causal placement",
        "",
        "`colorwheel -> flywheel -> ponder -> create -> ratatouille` remains verified by the parent family analyzer. The entry intervals are nested inside the same one-thread `block_entities` slice of the critical Create node; no source registration, callback, class-init, failure, or thread ordering is changed.",
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
