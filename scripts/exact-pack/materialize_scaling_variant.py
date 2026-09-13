#!/usr/bin/env python3
"""Materialize one artifact-safe scaling variant into a separate pack directory."""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import sys
from pathlib import Path

PLANNER_PATH = Path(__file__).with_name("plan_scaling.py")
SPEC = importlib.util.spec_from_file_location("bootoptim_scaling_planner", PLANNER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {PLANNER_PATH}")
PLANNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PLANNER
SPEC.loader.exec_module(PLANNER)


def materialize(source: Path, destination: Path, plan_path: Path, variant_id: str) -> dict:
    source = source.resolve()
    destination = destination.resolve()
    if not (source / "mods").is_dir():
        raise ValueError(f"source pack does not contain mods/: {source}")
    if destination == source or source in destination.parents:
        raise ValueError("destination must not be the source pack or a child of it")
    if destination.exists():
        raise ValueError(f"destination already exists; refusing to overwrite: {destination}")

    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    if plan.get("pack_directory") and Path(plan["pack_directory"]).resolve() != source:
        raise ValueError("plan pack_directory does not match source")
    if plan.get("selection_unit") not in {None, "top_level_physical_artifact"}:
        raise ValueError(f"unsupported plan selection unit: {plan.get('selection_unit')}")

    # A plan is invalid once the source JAR set changes. Re-scan before copying
    # so a manifest never claims the fingerprint of a different pack snapshot.
    _, _, current_fingerprint = PLANNER.scan_pack(source)
    if plan.get("pack_fingerprint") != current_fingerprint:
        raise ValueError(
            "source pack fingerprint changed after planning: "
            f"plan={plan.get('pack_fingerprint')} current={current_fingerprint}"
        )

    variant = next((item for item in plan.get("variants", []) if item.get("id") == variant_id), None)
    if variant is None:
        raise ValueError(f"unknown variant {variant_id!r}")
    if variant.get("missing_dependencies"):
        raise ValueError(f"variant has missing required dependencies: {variant['missing_dependencies']}")

    selected = set(variant.get("artifacts", []))
    source_jars = {jar.name: jar for jar in (source / "mods").glob("*.jar")}
    missing_artifacts = sorted(selected - source_jars.keys())
    if missing_artifacts:
        raise ValueError(f"plan selects artifacts missing from source: {missing_artifacts}")
    if any("bootoptim" in name.lower() or "boot_optim" in name.lower() for name in source_jars):
        raise ValueError("source pack contains BootOptim; remove the injected build before staging")

    decisions = variant.get("artifact_decisions", [])
    if decisions:
        decision_names = [item.get("artifact") for item in decisions]
        if len(decision_names) != len(set(decision_names)):
            raise ValueError("variant contains duplicate/shared artifact decisions")
        decision_selected = {item["artifact"] for item in decisions if item.get("status") == "included"}
        if decision_selected != selected:
            raise ValueError("artifact decisions do not match selected artifact set")
        if set(decision_names) != set(source_jars):
            raise ValueError("artifact decisions do not cover the complete physical source JAR set")

    inventory = {item["name"]: item for item in plan.get("artifacts", [])}
    selected_fingerprints = {
        artifact: inventory[artifact]["sha256"]
        for artifact in sorted(selected, key=str.lower)
        if artifact in inventory
    }

    destination.mkdir(parents=True)
    try:
        for child in source.iterdir():
            target = destination / child.name
            if child.name != "mods":
                if child.is_dir():
                    shutil.copytree(child, target, copy_function=shutil.copy2)
                else:
                    shutil.copy2(child, target)
                continue
            target.mkdir()
            for item in child.iterdir():
                if item.is_file() and item.suffix.lower() == ".jar":
                    if item.name in selected:
                        shutil.copy2(item, target / item.name)
                elif item.is_dir():
                    shutil.copytree(item, target / item.name, copy_function=shutil.copy2)
                else:
                    shutil.copy2(item, target / item.name)

        manifest = {
            "schema": 2,
            "selection_unit": plan.get("selection_unit", "legacy_mod_id"),
            "source_pack_fingerprint": current_fingerprint,
            "artifact_unit_fingerprint": plan.get("artifact_unit_fingerprint"),
            "source_pack_directory": str(source),
            "source_artifact_count": len(source_jars),
            "compatibility_groups": plan.get("compatibility_groups", []),
            "runtime_symbol_provider_edges": plan.get("runtime_symbol_provider_edges", []),
            "partition_validation": plan.get("partition_validation", {}),
            "explicitly_excluded_roots": plan.get("explicitly_excluded_roots", []),
            "variant_id": variant["id"],
            "variant_kind": variant.get("kind"),
            "requested_root_ids": variant.get("requested_root_ids", []),
            "roots": variant.get("roots", []),
            "runnable_roots": variant.get("runnable_roots", []),
            "assigned_artifacts": variant.get("assigned_artifacts", []),
            "direct_excluded_artifacts": variant.get("direct_excluded_artifacts", []),
            "effective_excluded_artifacts": variant.get(
                "effective_excluded_artifacts",
                sorted(set(source_jars) - selected, key=str.lower),
            ),
            "excluded_roots": variant.get("excluded_roots", []),
            "selected_mod_ids": variant.get("mod_ids", []),
            "selected_artifacts": sorted(selected, key=str.lower),
            "selected_artifact_fingerprints": selected_fingerprints,
            "excluded_artifacts": sorted(set(source_jars) - selected, key=str.lower),
            "artifact_decisions": decisions,
            "missing_dependencies": variant.get("missing_dependencies", []),
        }
        (destination / ".bootoptim-scaling-variant.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        return manifest
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = materialize(args.source, args.destination, args.plan, args.variant)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
