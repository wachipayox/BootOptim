"""Physical-artifact partition/closure layer for artifact_scaling.

This module deliberately treats repeated logical IDs as alternative providers,
not as evidence that the containing top-level JARs are one experimental unit.
"""

from __future__ import annotations

import json

import artifact_scaling as base


def artifact_units(records, artifact_to_ids, parsed_groups):
    artifacts = sorted(artifact_to_ids, key=str.lower)
    union = base._UnionFind(artifacts)
    primary = {}
    for artifact, mod_ids in artifact_to_ids.items():
        for mod_id in mod_ids:
            primary.setdefault(mod_id, set()).add(artifact)
    for group in parsed_groups:
        family = sorted({artifact for mod_id in group for artifact in primary.get(mod_id, set())}, key=str.lower)
        for artifact in family[1:]:
            union.union(family[0], artifact)
    grouped = {}
    for artifact in artifacts:
        grouped.setdefault(union.find(artifact), []).append(artifact)
    values = [sorted(group, key=str.lower) for group in grouped.values()]
    values.sort(key=lambda group: tuple(item.lower() for item in group))
    rendered = []
    for index, group in enumerate(values, 1):
        rendered.append({
            "id": f"artifact-unit-{index:03d}",
            "artifacts": group,
            "mod_ids": sorted({mod_id for artifact in group for mod_id in artifact_to_ids[artifact]}),
        })
    return rendered


def _unit_map(units):
    return {artifact: unit for unit in units for artifact in unit["artifacts"]}


def _expand_units(artifacts, units):
    lookup = _unit_map(units)
    expanded = set()
    for artifact in artifacts:
        unit = lookup.get(artifact)
        if unit:
            expanded.update(unit["artifacts"])
        else:
            expanded.add(artifact)
    return expanded


def _primary_provider_artifacts(records, artifact_to_ids, mod_id):
    primary = {artifact for artifact, ids in artifact_to_ids.items() if mod_id in ids}
    if primary:
        return primary
    return {record.artifact for record in records.get(mod_id, [])}


def _dependencies_for_artifact(records, artifact_to_ids, artifact):
    dependencies = set()
    for mod_id in artifact_to_ids[artifact]:
        for record in records.get(mod_id, []):
            if record.artifact == artifact:
                dependencies.update(record.dependencies.get(mod_id, set()))
    return dependencies


def prune_unrunnable(records, artifact_to_ids, units, candidate_artifacts):
    """Remove units whose required dependency has no remaining physical provider."""
    remaining = _expand_units(candidate_artifacts, units)
    unit_by_artifact = _unit_map(units)
    reasons = {}
    changed = True
    while changed:
        changed = False
        doomed_units = {}
        for artifact in sorted(remaining, key=str.lower):
            missing = []
            for dependency in sorted(_dependencies_for_artifact(records, artifact_to_ids, artifact)):
                if dependency in base.PLATFORM_PROVIDED_MOD_IDS:
                    continue
                providers = {record.artifact for record in records.get(dependency, [])}
                if not providers.intersection(remaining):
                    missing.append(dependency)
            if missing:
                unit = unit_by_artifact[artifact]
                doomed_units.setdefault(unit["id"], {"unit": unit, "failures": {}})["failures"][artifact] = missing
        if not doomed_units:
            break
        for payload in doomed_units.values():
            unit = payload["unit"]
            for artifact in unit["artifacts"]:
                if artifact in remaining:
                    remaining.remove(artifact)
                    reasons[artifact] = {
                        "reason": "closure_depends_on_excluded_or_missing" if artifact in payload["failures"] else "compatibility_unit_member_unrunnable",
                        "missing_dependencies": payload["failures"].get(artifact, []),
                        "unit_id": unit["id"],
                    }
                    changed = True
    return remaining, reasons


def physical_complement(name, kind, requested_roots, assigned_artifacts, records, artifact_to_ids, units, explicit_artifacts):
    assigned = _expand_units(assigned_artifacts, units)
    operator = _expand_units(explicit_artifacts, units)
    direct = assigned | operator
    selected, induced_reasons = prune_unrunnable(
        records, artifact_to_ids, units, set(artifact_to_ids) - direct
    )
    effective = set(artifact_to_ids) - selected
    decisions = []
    for artifact in sorted(artifact_to_ids, key=str.lower):
        if artifact in selected:
            status, reason, missing = "included", "retained_dependency_closed_remainder", []
        elif artifact in operator:
            status, reason, missing = "excluded", "operator_exclusion", []
        elif artifact in assigned:
            status, reason, missing = "excluded", "partition_unit_exclusion", []
        else:
            detail = induced_reasons.get(artifact, {})
            status = "excluded"
            reason = detail.get("reason", "closure_not_runnable")
            missing = detail.get("missing_dependencies", [])
        decisions.append({
            "artifact": artifact,
            "status": status,
            "reason": reason,
            "missing_dependencies": missing,
            "mod_ids": sorted(artifact_to_ids[artifact]),
        })
    return {
        "id": name,
        "kind": kind,
        "requested_root_ids": sorted(set(requested_roots)),
        "roots": sorted({mod_id for artifact in selected for mod_id in artifact_to_ids[artifact]}),
        "runnable_roots": sorted({mod_id for artifact in selected for mod_id in artifact_to_ids[artifact]}),
        "assigned_artifacts": sorted(assigned, key=str.lower),
        "direct_excluded_artifacts": sorted(direct, key=str.lower),
        "effective_excluded_artifacts": sorted(effective, key=str.lower),
        "excluded_roots": [],
        "mod_ids": sorted({mod_id for artifact in selected for mod_id in artifact_to_ids[artifact]}),
        "artifacts": sorted(selected, key=str.lower),
        "artifact_decisions": decisions,
        "missing_dependencies": [],
    }


def build_plan(pack_dir, groups, baseline, balanced_partitions=0, compatibility_groups=None, excluded_roots=None, runtime_symbol_providers=None, complement_groups=None):
    # Keep the legacy closure variants for compatibility, but construct every
    # partition/complement below from physical artifacts. No old balanced arm is
    # allowed to leak into the returned plan.
    plan = base._legacy_build_plan(
        pack_dir, groups, baseline, 0, compatibility_groups,
        excluded_roots, runtime_symbol_providers, []
    )
    records, artifact_to_ids, fingerprint = base.scan_pack(pack_dir.resolve())
    runtime_edges = base.add_runtime_symbol_provider_edges(
        pack_dir.resolve(), records, artifact_to_ids, runtime_symbol_providers or []
    )
    parsed_groups = [base.parse_group(raw)[1] for raw in (compatibility_groups or [])]
    units = artifact_units(records, artifact_to_ids, parsed_groups)
    inventory = base._artifact_inventory(records, artifact_to_ids, units)
    all_ids = sorted({mod_id for ids in artifact_to_ids.values() for mod_id in ids})
    explicit_ids = set(excluded_roots or [])
    unknown_explicit = sorted(value for value in explicit_ids if value not in records)
    if unknown_explicit:
        raise ValueError(f"excluded roots are not present in pack: {unknown_explicit}")
    explicit_artifacts = _expand_units(
        {artifact for mod_id in explicit_ids for artifact in _primary_provider_artifacts(records, artifact_to_ids, mod_id)},
        units,
    )

    plan.update({
        "schema": 2,
        "selection_unit": "top_level_physical_artifact",
        "pack_fingerprint": fingerprint,
        "artifact_count": len(artifact_to_ids),
        "artifact_unit_count": len(units),
        "artifact_unit_fingerprint": base._unit_fingerprint(units, inventory),
        "artifact_units": units,
        "artifacts": inventory,
        "runtime_symbol_provider_edges": runtime_edges,
    })

    variants = [variant for variant in plan["variants"] if variant.get("kind") not in {"balanced_partition", "balanced_complement", "custom_complement"}]

    for raw_group in complement_groups or []:
        group_name, roots = base.parse_group(raw_group)
        unknown = sorted(value for value in roots if value not in records)
        if unknown:
            raise ValueError(f"complement group {group_name} references unknown roots: {unknown}")
        seed = {artifact for mod_id in roots for artifact in _primary_provider_artifacts(records, artifact_to_ids, mod_id)}
        variants.append(physical_complement(
            f"complement-group-{group_name}", "custom_complement", roots, seed,
            records, artifact_to_ids, units, explicit_artifacts,
        ))

    validation = {"valid": True, "shared_artifacts": [], "assigned_artifact_count": 0}
    if balanced_partitions:
        if balanced_partitions > len(units):
            raise ValueError("balanced_partitions cannot exceed the number of physical artifact units")
        rows = []
        for index in range(balanced_partitions):
            assigned_units = units[index::balanced_partitions]
            assigned = sorted({artifact for unit in assigned_units for artifact in unit["artifacts"]}, key=str.lower)
            rows.append((f"partition-{index + 1}", assigned))
        validation = base.validate_disjoint_partition_assignments(rows)
        for index, (_, assigned) in enumerate(rows, 1):
            selected, reasons = prune_unrunnable(records, artifact_to_ids, units, set(assigned) - explicit_artifacts)
            variants.append({
                "id": f"partition-{index}",
                "kind": "balanced_partition",
                "roots": sorted({mod_id for artifact in assigned for mod_id in artifact_to_ids[artifact]}),
                "assigned_artifacts": assigned,
                "runnable_roots": sorted({mod_id for artifact in selected for mod_id in artifact_to_ids[artifact]}),
                "excluded_roots": [],
                "mod_ids": sorted({mod_id for artifact in selected for mod_id in artifact_to_ids[artifact]}),
                "artifacts": sorted(selected, key=str.lower),
                "physical_closure_removed_artifacts": sorted(reasons, key=str.lower),
                "missing_dependencies": [],
            })
            variants.append(physical_complement(
                f"complement-{index}", "balanced_complement",
                {mod_id for artifact in assigned for mod_id in artifact_to_ids[artifact]}, assigned,
                records, artifact_to_ids, units, explicit_artifacts,
            ))
    plan["partition_validation"] = validation
    plan["variants"] = variants
    return plan