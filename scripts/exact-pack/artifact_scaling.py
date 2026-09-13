#!/usr/bin/env python3
"""Artifact-safe planner for exact-pack scaling diagnostics.

The physical top-level JAR is the minimum selectable unit. Loader IDs remain
closure labels only: every ID (including nested jar-in-jar IDs) exposed by one
JAR travels with that JAR. Compatibility families and duplicate-ID providers
are collapsed into artifact units before balanced partitions or complements are
created. Runtime-symbol edges are applied before closure evaluation.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

SECTION_RE = re.compile(r"^\[+([^\]]+)\]+$")
KEY_VALUE_RE = re.compile(r"^([A-Za-z][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$")
QUOTED_RE = re.compile(r'^"([^"]*)"')
PLATFORM_PROVIDED_MOD_IDS = {
    "minecraft", "neoforge", "forge", "javafml", "fabricloader", "java",
}
BUILTIN_LANGUAGE_PROVIDERS = {"javafml", "lowcodefml", "neoforge", "forge"}


@dataclass
class ModRecord:
    artifact: str
    mod_ids: list[str]
    version: str | None
    dependencies: dict[str, set[str]] = field(default_factory=dict)
    optional_dependencies: dict[str, set[str]] = field(default_factory=dict)
    sha256: str = ""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _value(raw: str) -> str | None:
    match = QUOTED_RE.match(raw.strip())
    return match.group(1) if match else None


def _parse_metadata_text(text: str):
    section = ""
    current_mod: str | None = None
    current_dependency: str | None = None
    mods: list[tuple[str, str | None]] = []
    dependencies: dict[str, set[str]] = {}
    optional_dependencies: dict[str, set[str]] = {}
    aliases: dict[str, set[str]] = {}
    dependency_mode = "required"
    language_provider: str | None = None

    def record_dependency() -> None:
        if not current_mod or not current_dependency:
            return
        required = dependencies.setdefault(current_mod, set())
        optional = optional_dependencies.setdefault(current_mod, set())
        if dependency_mode == "required":
            required.add(current_dependency)
            optional.discard(current_dependency)
        elif dependency_mode == "optional":
            optional.add(current_dependency)
            required.discard(current_dependency)
        else:
            required.discard(current_dependency)
            optional.discard(current_dependency)

    for original in text.splitlines():
        line = original.split("#", 1)[0].strip()
        if not line:
            continue
        if line == "[[mods]]":
            section = "mods"
            current_mod = None
            current_dependency = None
            continue
        section_match = SECTION_RE.match(line)
        if section_match:
            section = section_match.group(1)
            current_dependency = None
            dependency_mode = "required"
            if section.startswith("dependencies."):
                owner = section[len("dependencies."):].split(".", 1)[0].strip()
                if len(owner) >= 2 and owner[0] == owner[-1] and owner[0] in {'"', "'"}:
                    owner = owner[1:-1]
                current_mod = owner
            continue
        match = KEY_VALUE_RE.match(line)
        if not match:
            continue
        key, raw_value = match.groups()
        value = _value(raw_value)
        if key == "modLoader" and value:
            language_provider = value
            continue
        if section == "mods":
            if key == "modId" and value:
                current_mod = value
                mods.append((value, None))
            elif key == "version" and value and current_mod:
                for index in range(len(mods) - 1, -1, -1):
                    if mods[index][0] == current_mod and mods[index][1] is None:
                        mods[index] = (current_mod, value)
                        break
            elif key == "provides" and current_mod:
                aliases.setdefault(current_mod, set()).update(re.findall(r'"([^"\\]+)"', raw_value))
        elif section.startswith("dependencies."):
            if key == "modId" and value:
                current_dependency = value
                record_dependency()
            elif key == "mandatory":
                dependency_mode = "required" if raw_value.strip().strip('"').strip("'").lower() == "true" else "optional"
                record_dependency()
            elif key == "type" and value:
                dependency_mode = "required" if value.lower() == "required" else "optional" if value.lower() == "optional" else "excluded"
                record_dependency()
    provider_id = language_provider.lower() if language_provider else None
    if provider_id and provider_id not in BUILTIN_LANGUAGE_PROVIDERS:
        for mod_id, _ in mods:
            dependencies.setdefault(mod_id, set()).add(provider_id)
    return mods, dependencies, optional_dependencies, aliases


def _parse_fabric_metadata_text(text: str):
    payload = json.loads(text)
    mod_id = payload.get("id")
    if not isinstance(mod_id, str) or not mod_id:
        return [], {}, {}, {}
    version = payload.get("version")
    required = {value for value in (payload.get("depends") or {}) if isinstance(value, str)}
    optional = {value for value in (payload.get("recommends") or {}) if isinstance(value, str)}
    provides = payload.get("provides") or []
    aliases = {value for value in provides if isinstance(value, str) and value} if isinstance(provides, list) else set()
    return [(mod_id, version if isinstance(version, str) else None)], {mod_id: required}, {mod_id: optional}, {mod_id: aliases}


def read_metadata(jar: Path):
    metadata_names = ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
    merged_mods: list[tuple[str, str | None]] = []
    merged_required: dict[str, set[str]] = {}
    merged_optional: dict[str, set[str]] = {}
    merged_aliases: dict[str, set[str]] = {}

    def merge(payload) -> None:
        mods, required, optional, aliases = payload
        merged_mods.extend(mods)
        for owner, values in required.items():
            merged_required.setdefault(owner, set()).update(values)
        for owner, values in optional.items():
            merged_optional.setdefault(owner, set()).update(values)
        for owner, values in aliases.items():
            merged_aliases.setdefault(owner, set()).update(values)

    def read_one(archive: zipfile.ZipFile, names: set[str]) -> None:
        for candidate in metadata_names:
            if candidate in names:
                merge(_parse_metadata_text(archive.read(candidate).decode("utf-8", errors="replace")))
                break
        if "fabric.mod.json" in names:
            merge(_parse_fabric_metadata_text(archive.read("fabric.mod.json").decode("utf-8", errors="replace")))

    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        read_one(archive, names)
        for nested_name in sorted(name for name in names if name.lower().endswith(".jar")):
            try:
                with zipfile.ZipFile(io.BytesIO(archive.read(nested_name))) as nested:
                    read_one(nested, set(nested.namelist()))
            except (OSError, zipfile.BadZipFile):
                continue
    # Metadata can survive conversion in both FML and Fabric forms. De-duplicate
    # IDs here so one physical artifact never acquires duplicate logical labels.
    unique_mods: list[tuple[str, str | None]] = []
    seen: set[str] = set()
    for mod_id, version in merged_mods:
        if mod_id not in seen:
            unique_mods.append((mod_id, version))
            seen.add(mod_id)
    return unique_mods, merged_required, merged_optional, merged_aliases


def scan_pack(pack_dir: Path):
    mods_dir = pack_dir / "mods"
    if not mods_dir.is_dir():
        raise ValueError(f"pack does not contain mods/: {pack_dir}")
    records: dict[str, list[ModRecord]] = {}
    artifact_to_ids: dict[str, list[str]] = {}
    fingerprint = hashlib.sha256()
    for jar in sorted(mods_dir.glob("*.jar"), key=lambda value: value.name.lower()):
        if "bootoptim" in jar.name.lower() or "boot_optim" in jar.name.lower():
            raise ValueError(f"source pack must not contain a BootOptim jar: {jar.name}")
        mod_entries, dependencies, optional_dependencies, provided_aliases = read_metadata(jar)
        if not mod_entries:
            mod_entries = [(jar.stem.lower(), None)]
        mod_ids = [entry[0] for entry in mod_entries]
        record = ModRecord(
            artifact=jar.name,
            mod_ids=mod_ids,
            version=next((entry[1] for entry in mod_entries if entry[1]), None),
            dependencies=dependencies,
            optional_dependencies=optional_dependencies,
            sha256=sha256(jar),
        )
        for mod_id in mod_ids:
            records.setdefault(mod_id, []).append(record)
            for alias in provided_aliases.get(mod_id, set()):
                records.setdefault(alias, []).append(record)
        artifact_to_ids[jar.name] = mod_ids
        fingerprint.update(jar.name.encode())
        fingerprint.update(b"\0")
        fingerprint.update(record.sha256.encode())
        fingerprint.update(b"\0")
    return records, artifact_to_ids, fingerprint.hexdigest()


def required_closure(records, roots, compatibility_groups=None, excluded_ids=None):
    selected: set[str] = set()
    missing: set[str] = set()
    pending = list(roots)
    groups = compatibility_groups or []
    excluded = excluded_ids or set()
    while pending:
        mod_id = pending.pop()
        if mod_id in PLATFORM_PROVIDED_MOD_IDS:
            continue
        if mod_id in excluded:
            missing.add(mod_id)
            continue
        if mod_id in selected:
            continue
        matching_records = records.get(mod_id)
        if not matching_records:
            missing.add(mod_id)
            continue
        selected.add(mod_id)
        for record in matching_records:
            pending.extend(value for value in record.mod_ids if value not in selected)
        for group in groups:
            if mod_id in group:
                pending.extend(value for value in group if value not in selected)
        dependencies: set[str] = set()
        for record in matching_records:
            dependencies.update(record.dependencies.get(mod_id, set()))
            dependencies.update(
                dependency for dependency in record.optional_dependencies.get(mod_id, set())
                if dependency in records and dependency not in excluded
            )
        pending.extend(value for value in dependencies if value not in selected)
    return selected, sorted(missing)


def artifact_selection(records, mod_ids):
    return sorted({record.artifact for mod_id in mod_ids for record in records[mod_id]}, key=str.lower)


def parse_group(raw: str):
    if "=" not in raw:
        raise ValueError(f"group must use NAME=mod1,mod2: {raw}")
    name, ids = raw.split("=", 1)
    roots = [value.strip() for value in ids.split(",") if value.strip()]
    if not name.strip() or not roots:
        raise ValueError(f"group must use NAME=mod1,mod2: {raw}")
    return name.strip(), roots


def parse_runtime_symbol_provider(raw: str):
    if "=" not in raw:
        raise ValueError(f"runtime symbol provider must use MODID=package/: {raw}")
    mod_id, prefix = raw.split("=", 1)
    mod_id, prefix = mod_id.strip(), prefix.strip()
    if not mod_id or not prefix or not prefix.endswith("/"):
        raise ValueError(f"runtime symbol provider must use MODID=package/: {raw}")
    return mod_id, prefix.encode("utf-8")


def artifact_references_symbol(artifact: Path, prefix: bytes) -> bool:
    def archive_references(archive: zipfile.ZipFile) -> bool:
        return any(prefix in archive.read(name) for name in archive.namelist() if name.endswith(".class"))
    with zipfile.ZipFile(artifact) as archive:
        if archive_references(archive):
            return True
        for name in archive.namelist():
            if not name.lower().endswith(".jar"):
                continue
            try:
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                    if archive_references(nested):
                        return True
            except (OSError, zipfile.BadZipFile):
                continue
    return False


def add_runtime_symbol_provider_edges(pack_dir, records, artifact_to_ids, providers):
    applied: list[dict[str, str]] = []
    for provider_id, prefix in [parse_runtime_symbol_provider(raw) for raw in providers]:
        if provider_id not in records:
            raise ValueError(f"runtime symbol provider is not present in pack: {provider_id}")
        provider_artifacts = {record.artifact for record in records[provider_id]}
        for artifact_name, mod_ids in artifact_to_ids.items():
            if artifact_name in provider_artifacts:
                continue
            if not artifact_references_symbol(pack_dir / "mods" / artifact_name, prefix):
                continue
            for mod_id in mod_ids:
                for record in records[mod_id]:
                    if record.artifact == artifact_name:
                        record.dependencies.setdefault(mod_id, set()).add(provider_id)
                applied.append({"artifact": artifact_name, "mod_id": mod_id, "provider": provider_id, "prefix": prefix.decode()})
    return sorted(applied, key=lambda item: (item["artifact"].lower(), item["mod_id"], item["provider"]))


class _UnionFind:
    def __init__(self, values):
        self.parent = {value: value for value in values}

    def find(self, value):
        parent = self.parent[value]
        if parent != value:
            self.parent[value] = self.find(parent)
        return self.parent[value]

    def union(self, left, right):
        left_root, right_root = self.find(left), self.find(right)
        if left_root != right_root:
            if right_root.lower() < left_root.lower():
                left_root, right_root = right_root, left_root
            self.parent[right_root] = left_root


def _artifact_units(records, artifact_to_ids, parsed_compatibility_groups):
    artifacts = sorted(artifact_to_ids, key=str.lower)
    union = _UnionFind(artifacts)
    # Duplicate loader IDs cannot be independently selected: resolving that ID
    # selects every provider artifact, so those physical artifacts are one DOE unit.
    for mod_id in {value for values in artifact_to_ids.values() for value in values}:
        providers = sorted({record.artifact for record in records.get(mod_id, [])}, key=str.lower)
        for artifact in providers[1:]:
            union.union(providers[0], artifact)
    # Explicit runtime/compatibility families are also collapsed before any
    # partition is generated, so no arm can contain only one family member.
    for group in parsed_compatibility_groups:
        family_artifacts = sorted({record.artifact for mod_id in group for record in records.get(mod_id, [])}, key=str.lower)
        if len(family_artifacts) < len({mod_id for mod_id in group if mod_id in records}):
            pass
        for artifact in family_artifacts[1:]:
            union.union(family_artifacts[0], artifact)
    grouped: dict[str, list[str]] = {}
    for artifact in artifacts:
        grouped.setdefault(union.find(artifact), []).append(artifact)
    units = [sorted(values, key=str.lower) for values in grouped.values()]
    units.sort(key=lambda values: tuple(value.lower() for value in values))
    artifact_to_unit: dict[str, str] = {}
    rendered = []
    for index, values in enumerate(units, 1):
        unit_id = f"artifact-unit-{index:03d}"
        mod_ids = sorted({mod_id for artifact in values for mod_id in artifact_to_ids[artifact]})
        rendered.append({"id": unit_id, "artifacts": values, "mod_ids": mod_ids})
        for artifact in values:
            artifact_to_unit[artifact] = unit_id
    return rendered, artifact_to_unit


def validate_disjoint_partition_assignments(assignments):
    seen: dict[str, str] = {}
    shared: list[dict[str, str]] = []
    for partition_id, artifacts in assignments:
        for artifact in artifacts:
            if artifact in seen:
                shared.append({"artifact": artifact, "first_partition": seen[artifact], "second_partition": partition_id})
            else:
                seen[artifact] = partition_id
    if shared:
        raise ValueError(f"partition shares physical artifacts across arms: {shared}")
    return {"valid": True, "shared_artifacts": [], "assigned_artifact_count": len(seen)}


def _ids_for_artifacts(records, artifacts):
    artifacts = set(artifacts)
    return {mod_id for mod_id, values in records.items() if any(record.artifact in artifacts for record in values)}


def _artifacts_for_ids(records, ids):
    return {record.artifact for mod_id in ids for record in records.get(mod_id, [])}


def _unit_expand(artifacts, units):
    expanded = set(artifacts)
    changed = True
    while changed:
        changed = False
        for unit in units:
            unit_artifacts = set(unit["artifacts"])
            if expanded.intersection(unit_artifacts) and not unit_artifacts.issubset(expanded):
                expanded.update(unit_artifacts)
                changed = True
    return expanded


def _artifact_inventory(records, artifact_to_ids, units):
    unit_by_artifact = {artifact: unit["id"] for unit in units for artifact in unit["artifacts"]}
    result = []
    for artifact in sorted(artifact_to_ids, key=str.lower):
        record = next(record for record in records[artifact_to_ids[artifact][0]] if record.artifact == artifact)
        result.append({"name": artifact, "sha256": record.sha256, "mod_ids": sorted(artifact_to_ids[artifact]), "unit_id": unit_by_artifact[artifact]})
    return result


def _unit_fingerprint(units, inventory):
    sha_by_artifact = {item["name"]: item["sha256"] for item in inventory}
    payload = [{"id": unit["id"], "artifacts": [(artifact, sha_by_artifact[artifact]) for artifact in unit["artifacts"]]} for unit in units]
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _complement_variant(name, kind, requested_roots, assigned_artifacts, records, artifact_to_ids, all_ids, units, parsed_groups, explicit_artifacts):
    assigned_artifacts = _unit_expand(set(assigned_artifacts), units)
    operator_artifacts = _unit_expand(set(explicit_artifacts), units)
    direct_excluded = assigned_artifacts | operator_artifacts
    excluded_ids = _ids_for_artifacts(records, direct_excluded)
    candidate_roots = [mod_id for mod_id in all_ids if mod_id not in excluded_ids]
    runnable_roots: list[str] = []
    root_failures: dict[str, list[str]] = {}
    excluded_roots = []
    for root in sorted(set(requested_roots)):
        excluded_roots.append({"id": root, "reason": "complement_excluded", "missing_dependencies": []})
    for root in sorted(_ids_for_artifacts(records, operator_artifacts) & set(all_ids)):
        if root not in requested_roots:
            excluded_roots.append({"id": root, "reason": "operator_excluded", "missing_dependencies": []})
    for root in candidate_roots:
        _, missing = required_closure(records, [root], parsed_groups, excluded_ids)
        if missing:
            root_failures[root] = missing
            excluded_roots.append({"id": root, "reason": "depends_on_excluded_or_missing", "missing_dependencies": missing})
        else:
            runnable_roots.append(root)
    selected_ids, missing = required_closure(records, runnable_roots, parsed_groups, excluded_ids)
    if missing:
        raise ValueError(f"{name} has an unexpected missing dependency closure: {missing}")
    selected_artifacts = set(artifact_selection(records, selected_ids))
    leaked = sorted(selected_artifacts & direct_excluded, key=str.lower)
    if leaked:
        raise ValueError(f"{name} selected artifacts that were assigned to its exclusion arm: {leaked}")
    decisions = []
    failed_artifacts = _artifacts_for_ids(records, root_failures)
    for artifact in sorted(artifact_to_ids, key=str.lower):
        if artifact in selected_artifacts:
            reason = "retained_dependency_closed_remainder"
            status = "included"
        elif artifact in assigned_artifacts:
            reason = "partition_unit_exclusion"
            status = "excluded"
        elif artifact in operator_artifacts:
            reason = "operator_exclusion"
            status = "excluded"
        elif artifact in failed_artifacts:
            reason = "closure_depends_on_excluded_or_missing"
            status = "excluded"
        else:
            reason = "closure_not_runnable"
            status = "excluded"
        decisions.append({"artifact": artifact, "status": status, "reason": reason, "mod_ids": sorted(artifact_to_ids[artifact])})
    effective_excluded = sorted(set(artifact_to_ids) - selected_artifacts, key=str.lower)
    return {
        "id": name,
        "kind": kind,
        "requested_root_ids": sorted(set(requested_roots)),
        "roots": sorted(candidate_roots),
        "runnable_roots": sorted(runnable_roots),
        "assigned_artifacts": sorted(assigned_artifacts, key=str.lower),
        "direct_excluded_artifacts": sorted(direct_excluded, key=str.lower),
        "effective_excluded_artifacts": effective_excluded,
        "excluded_roots": sorted(excluded_roots, key=lambda item: (item["id"], item["reason"])),
        "mod_ids": sorted(selected_ids),
        "artifacts": sorted(selected_artifacts, key=str.lower),
        "artifact_decisions": decisions,
        "missing_dependencies": [],
    }


def build_plan(pack_dir: Path, groups: list[str], baseline: list[str], balanced_partitions: int = 0, compatibility_groups: list[str] | None = None, excluded_roots: list[str] | None = None, runtime_symbol_providers: list[str] | None = None, complement_groups: list[str] | None = None):
    if balanced_partitions < 0:
        raise ValueError("balanced_partitions must not be negative")
    pack_dir = pack_dir.resolve()
    records, artifact_to_ids, fingerprint = scan_pack(pack_dir)
    applied_runtime_edges = add_runtime_symbol_provider_edges(pack_dir, records, artifact_to_ids, runtime_symbol_providers or [])
    parsed_groups = [parse_group(raw)[1] for raw in (compatibility_groups or [])]
    units, artifact_to_unit = _artifact_units(records, artifact_to_ids, parsed_groups)
    inventory = _artifact_inventory(records, artifact_to_ids, units)
    all_ids = sorted({mod_id for values in artifact_to_ids.values() for mod_id in values})
    explicit_ids = set(excluded_roots or [])
    unknown_explicit = sorted(value for value in explicit_ids if value not in records)
    if unknown_explicit:
        raise ValueError(f"excluded roots are not present in pack: {unknown_explicit}")
    explicit_artifacts = _unit_expand(_artifacts_for_ids(records, explicit_ids), units)
    variants = [{
        "id": "full", "kind": "full", "roots": all_ids, "mod_ids": all_ids,
        "artifacts": sorted(artifact_to_ids, key=str.lower), "missing_dependencies": [],
        "artifact_decisions": [{"artifact": artifact, "status": "included", "reason": "full_pack", "mod_ids": sorted(artifact_to_ids[artifact])} for artifact in sorted(artifact_to_ids, key=str.lower)],
    }]
    baseline_ids, baseline_missing = required_closure(records, baseline, parsed_groups)
    variants.append({
        "id": "baseline", "kind": "baseline", "roots": sorted(baseline), "mod_ids": sorted(baseline_ids),
        "artifacts": artifact_selection(records, baseline_ids), "missing_dependencies": baseline_missing,
    })
    for mod_id in all_ids:
        selected, missing = required_closure(records, [mod_id], parsed_groups)
        variants.append({"id": f"mod-{mod_id}", "kind": "single_mod_closure", "roots": [mod_id], "mod_ids": sorted(selected), "artifacts": artifact_selection(records, selected), "missing_dependencies": missing})
    for raw_group in groups:
        group_name, roots = parse_group(raw_group)
        selected, missing = required_closure(records, roots, parsed_groups)
        variants.append({"id": f"group-{group_name}", "kind": "interaction_group_closure", "roots": sorted(roots), "mod_ids": sorted(selected), "artifacts": artifact_selection(records, selected), "missing_dependencies": missing})
    for raw_group in complement_groups or []:
        group_name, roots = parse_group(raw_group)
        unknown = sorted(value for value in roots if value not in records)
        if unknown:
            raise ValueError(f"complement group {group_name} references unknown roots: {unknown}")
        seed_artifacts = _artifacts_for_ids(records, roots)
        variants.append(_complement_variant(f"complement-group-{group_name}", "custom_complement", roots, seed_artifacts, records, artifact_to_ids, all_ids, units, parsed_groups, explicit_artifacts))

    partition_validation = {"valid": True, "shared_artifacts": [], "assigned_artifact_count": 0}
    if balanced_partitions:
        if balanced_partitions > len(artifact_to_ids):
            raise ValueError("balanced_partitions cannot exceed the number of top-level artifacts")
        assignment_rows = []
        for index in range(balanced_partitions):
            assigned_units = units[index::balanced_partitions]
            assigned_artifacts = sorted({artifact for unit in assigned_units for artifact in unit["artifacts"]}, key=str.lower)
            assignment_rows.append((f"partition-{index + 1}", assigned_artifacts))
        partition_validation = validate_disjoint_partition_assignments(assignment_rows)
        for index, (partition_id, assigned_artifacts) in enumerate(assignment_rows, 1):
            assigned_ids = sorted({mod_id for artifact in assigned_artifacts for mod_id in artifact_to_ids[artifact]})
            runnable_roots = []
            excluded_records = []
            for root in assigned_ids:
                if root in explicit_ids:
                    excluded_records.append({"id": root, "reason": "operator_excluded", "missing_dependencies": []})
                    continue
                _, root_missing = required_closure(records, [root], parsed_groups, _ids_for_artifacts(records, explicit_artifacts))
                if root_missing:
                    excluded_records.append({"id": root, "missing_dependencies": root_missing})
                else:
                    runnable_roots.append(root)
            selected, missing = required_closure(records, runnable_roots, parsed_groups, _ids_for_artifacts(records, explicit_artifacts))
            if missing:
                raise ValueError(f"balanced partition {index} has unexpected missing closure: {missing}")
            variants.append({
                "id": partition_id, "kind": "balanced_partition", "roots": assigned_ids,
                "assigned_artifacts": assigned_artifacts, "runnable_roots": runnable_roots,
                "excluded_roots": excluded_records, "mod_ids": sorted(selected),
                "artifacts": artifact_selection(records, selected), "missing_dependencies": [],
            })
            variants.append(_complement_variant(f"complement-{index}", "balanced_complement", assigned_ids, set(assigned_artifacts), records, artifact_to_ids, all_ids, units, parsed_groups, explicit_artifacts))

    return {
        "schema": 2,
        "selection_unit": "top_level_physical_artifact",
        "pack_directory": str(pack_dir),
        "pack_fingerprint": fingerprint,
        "artifact_unit_fingerprint": _unit_fingerprint(units, inventory),
        "mod_count": len(all_ids),
        "artifact_count": len(artifact_to_ids),
        "artifact_unit_count": len(units),
        "partition_validation": partition_validation,
        "compatibility_groups": [{"name": raw.split("=", 1)[0].strip(), "mod_ids": sorted(values)} for raw, values in zip(compatibility_groups or [], parsed_groups)],
        "explicitly_excluded_roots": sorted(explicit_ids),
        "runtime_symbol_provider_edges": applied_runtime_edges,
        "artifact_units": units,
        "artifacts": inventory,
        "mods": [{
            "id": mod_id,
            "artifacts": sorted({record.artifact for record in records[mod_id]}, key=str.lower),
            "versions": sorted({record.version for record in records[mod_id] if record.version}),
            "required_dependencies": sorted({dependency for record in records[mod_id] for dependency in record.dependencies.get(mod_id, set())}),
            "optional_dependencies": sorted({dependency for record in records[mod_id] for dependency in record.optional_dependencies.get(mod_id, set())}),
        } for mod_id in all_ids],
        "variants": variants,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", action="append", default=[])
    parser.add_argument("--group", action="append", default=[])
    parser.add_argument("--balanced-partitions", type=int, default=0)
    parser.add_argument("--compatibility-group", action="append", default=[])
    parser.add_argument("--exclude-root", action="append", default=[])
    parser.add_argument("--runtime-symbol-provider", action="append", default=[])
    parser.add_argument("--complement-group", action="append", default=[])
    args = parser.parse_args()
    try:
        plan = build_plan(args.pack_dir, args.group, args.baseline, args.balanced_partitions, args.compatibility_group, args.exclude_root, args.runtime_symbol_provider, args.complement_group)
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
    serialized = json.dumps(plan, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    else:
        print(serialized, end="")


if __name__ == "__main__":
    main()
