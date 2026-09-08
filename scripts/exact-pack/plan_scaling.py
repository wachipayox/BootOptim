#!/usr/bin/env python3
"""Build a reproducible mod-scaling plan from an extracted exact-pack directory.

The planner is intentionally read-only. It never edits the fixture and does not launch Minecraft.
Each generated variant selects a complete transitive closure of required mod dependencies so a later
stager can create an isolated copy without silently dropping a loader contract.
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
# These are supplied by the Minecraft/NeoForge runtime rather than by a
# selectable mod artifact in the exact-pack `mods/` directory.
PLATFORM_PROVIDED_MOD_IDS = {"minecraft", "neoforge", "forge", "javafml"}
# Loader-level language providers are supplied by NeoForge itself and must not
# turn into selectable closure edges.
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


def _parse_metadata_text(
    text: str,
) -> tuple[list[tuple[str, str | None]], dict[str, set[str]], dict[str, set[str]]]:
    section = ""
    current_mod: str | None = None
    current_dependency: str | None = None
    mods: list[tuple[str, str | None]] = []
    dependencies: dict[str, set[str]] = {}
    optional_dependencies: dict[str, set[str]] = {}
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
                # NeoForge's metadata commonly quotes the mod id in a
                # [[dependencies."mod_id"]] table.  Keep closure lookup
                # keyed by the same bare id used by [[mods]].
                if len(owner) >= 2 and owner[0] == owner[-1] and owner[0] in {'"', "'"}:
                    owner = owner[1:-1]
                current_mod = owner
                current_dependency = None
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
        elif section.startswith("dependencies."):
            if key == "modId" and value:
                current_dependency = value
                record_dependency()
            elif key == "mandatory":
                mandatory_text = raw_value.strip().strip('"').strip("'").lower()
                dependency_mode = "required" if mandatory_text == "true" else "optional"
                record_dependency()
            elif key == "type" and value:
                # NeoForge's current schema uses type=required/optional/
                # incompatible/discouraged instead of mandatory=false.
                dependency_type = value.lower()
                dependency_mode = (
                    "required"
                    if dependency_type == "required"
                    else "optional"
                    if dependency_type == "optional"
                    else "excluded"
                )
                record_dependency()
    # `modLoader="kotlinforforge"` is a hard loader contract, not a normal
    # [[dependencies]] table. Without this edge a reduced variant can copy a
    # Kotlin mod and fail in NeoForge's loading screen instead of producing a
    # valid attribution point.
    if language_provider and language_provider.lower() not in BUILTIN_LANGUAGE_PROVIDERS:
        for mod_id, _ in mods:
            dependencies.setdefault(mod_id, set()).add(language_provider)
    return mods, dependencies, optional_dependencies


def read_metadata(
    jar: Path,
) -> tuple[list[tuple[str, str | None]], dict[str, set[str]], dict[str, set[str]]]:
    """Read top-level and nested NeoForge/FML metadata from one artifact.

    Modern NeoForge mods commonly ship library mods such as Flywheel or Ponder
    inside ``META-INF/jarjar``.  They are provided by the same top-level JAR,
    so treating their dependency IDs as missing would create reduced variants
    that the real pack never uses.  The planner keeps the top-level artifact as
    the selection unit while exposing every embedded mod ID for closure checks.
    """
    metadata_names = ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
    merged_mods: list[tuple[str, str | None]] = []
    merged_required: dict[str, set[str]] = {}
    merged_optional: dict[str, set[str]] = {}

    def merge(payload: tuple[list[tuple[str, str | None]], dict[str, set[str]], dict[str, set[str]]]) -> None:
        mods, required, optional = payload
        merged_mods.extend(mods)
        for owner, values in required.items():
            merged_required.setdefault(owner, set()).update(values)
        for owner, values in optional.items():
            merged_optional.setdefault(owner, set()).update(values)

    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        for candidate in metadata_names:
            if candidate in names:
                merge(_parse_metadata_text(archive.read(candidate).decode("utf-8", errors="replace")))
                break
        for nested_name in sorted(name for name in names if name.lower().endswith(".jar")):
            try:
                nested_bytes = archive.read(nested_name)
                with zipfile.ZipFile(io.BytesIO(nested_bytes)) as nested:
                    nested_names = set(nested.namelist())
                    for candidate in metadata_names:
                        if candidate in nested_names:
                            merge(_parse_metadata_text(
                                nested.read(candidate).decode("utf-8", errors="replace")
                            ))
                            break
            except (OSError, zipfile.BadZipFile):
                # A non-JAR payload with a .jar suffix is not a loader artifact.
                continue
    return merged_mods, merged_required, merged_optional


def scan_pack(pack_dir: Path) -> tuple[dict[str, list[ModRecord]], dict[str, list[str]], str]:
    mods_dir = pack_dir / "mods"
    if not mods_dir.is_dir():
        raise ValueError(f"pack does not contain mods/: {pack_dir}")
    records: dict[str, list[ModRecord]] = {}
    artifact_to_ids: dict[str, list[str]] = {}
    fingerprint = hashlib.sha256()
    for jar in sorted(mods_dir.glob("*.jar"), key=lambda value: value.name.lower()):
        if "bootoptim" in jar.name.lower() or "boot_optim" in jar.name.lower():
            raise ValueError(f"source pack must not contain a BootOptim jar: {jar.name}")
        mod_entries, dependencies, optional_dependencies = read_metadata(jar)
        if not mod_entries:
            mod_id = jar.stem.lower()
            mod_entries = [(mod_id, None)]
        mod_ids = [entry[0] for entry in mod_entries]
        version = next((entry[1] for entry in mod_entries if entry[1]), None)
        record = ModRecord(
            jar.name,
            mod_ids,
            version,
            dependencies,
            optional_dependencies,
            sha256(jar),
        )
        for mod_id in mod_ids:
            records.setdefault(mod_id, []).append(record)
        artifact_to_ids[jar.name] = mod_ids
        fingerprint.update(jar.name.encode())
        fingerprint.update(b"\0")
        fingerprint.update(record.sha256.encode())
        fingerprint.update(b"\0")
    return records, artifact_to_ids, fingerprint.hexdigest()


def required_closure(
    records: dict[str, list[ModRecord]],
    roots: list[str],
    compatibility_groups: list[list[str]] | None = None,
    excluded_ids: set[str] | None = None,
) -> tuple[set[str], list[str]]:
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
            # An explicitly host-incompatible mod is absent from this
            # partition. Required edges become an unmaterializable root;
            # optional edges are filtered below before they reach pending.
            missing.add(mod_id)
            continue
        if mod_id in selected:
            continue
        matching_records = records.get(mod_id)
        if not matching_records:
            missing.add(mod_id)
            continue
        selected.add(mod_id)
        # A top-level JAR is the materialization unit, and it can expose more
        # than one loader mod (for example a bundled helper alongside the
        # visible mod).  Selecting the artifact therefore also selects every
        # mod ID declared by that artifact.  Otherwise artifact_selection()
        # would copy a JAR whose hidden mod is loaded at runtime without its
        # own dependency closure, producing a false-valid reduced variant.
        for record in matching_records:
            for co_located_id in record.mod_ids:
                if co_located_id not in selected:
                    pending.append(co_located_id)
        for group in groups:
            if mod_id in group:
                pending.extend(member for member in group if member not in selected)
        dependencies = set()
        for record in matching_records:
            dependencies.update(record.dependencies.get(mod_id, set()))
            dependencies.update(
                dependency
                for dependency in record.optional_dependencies.get(mod_id, set())
                if dependency in records and dependency not in excluded
            )
        for dependency in dependencies:
            if dependency not in selected:
                pending.append(dependency)
    return selected, sorted(missing)


def artifact_selection(records: dict[str, list[ModRecord]], mod_ids: set[str]) -> list[str]:
    return sorted(
        {record.artifact for mod_id in mod_ids for record in records[mod_id]},
        key=str.lower,
    )


def parse_group(raw: str) -> tuple[str, list[str]]:
    if "=" not in raw:
        raise ValueError(f"group must use NAME=mod1,mod2: {raw}")
    name, ids = raw.split("=", 1)
    roots = [value.strip() for value in ids.split(",") if value.strip()]
    if not name.strip() or not roots:
        raise ValueError(f"group must use NAME=mod1,mod2: {raw}")
    return name.strip(), roots


def expand_compatibility_exclusions(
    excluded: set[str], compatibility_groups: list[list[str]],
) -> set[str]:
    """Expand a complement exclusion to whole evidence-backed runtime families.

    A complement must not leave one member of a known runtime family behind:
    the surviving member could pull the excluded member through an undeclared
    integration edge and turn the result into a misleading partial pack.
    """
    expanded = set(excluded)
    changed = True
    while changed:
        changed = False
        for group in compatibility_groups:
            if expanded.intersection(group):
                before = len(expanded)
                expanded.update(group)
                changed |= len(expanded) != before
    return expanded


def build_plan(
    pack_dir: Path,
    groups: list[str],
    baseline: list[str],
    balanced_partitions: int = 0,
    compatibility_groups: list[str] | None = None,
    excluded_roots: list[str] | None = None,
) -> dict:
    if balanced_partitions < 0:
        raise ValueError("balanced_partitions must not be negative")
    records, artifact_to_ids, fingerprint = scan_pack(pack_dir.resolve())
    parsed_compatibility_groups = [parse_group(raw)[1] for raw in (compatibility_groups or [])]
    explicitly_excluded = set(excluded_roots or [])
    variants = []

    all_ids = sorted(records)
    variants.append({
        "id": "full",
        "kind": "full",
        "roots": all_ids,
        "mod_ids": all_ids,
        "artifacts": sorted(artifact_to_ids, key=str.lower),
        "missing_dependencies": [],
    })

    baseline_ids, baseline_missing = required_closure(records, baseline, parsed_compatibility_groups)
    variants.append({
        "id": "baseline",
        "kind": "baseline",
        "roots": sorted(baseline),
        "mod_ids": sorted(baseline_ids),
        "artifacts": artifact_selection(records, baseline_ids),
        "missing_dependencies": baseline_missing,
    })

    for mod_id in all_ids:
        selected, missing = required_closure(records, [mod_id], parsed_compatibility_groups)
        variants.append({
            "id": f"mod-{mod_id}",
            "kind": "single_mod_closure",
            "roots": [mod_id],
            "mod_ids": sorted(selected),
            "artifacts": artifact_selection(records, selected),
            "missing_dependencies": missing,
        })
    for raw_group in groups:
        name, roots = parse_group(raw_group)
        selected, missing = required_closure(records, roots, parsed_compatibility_groups)
        variants.append({
            "id": f"group-{name}",
            "kind": "interaction_group_closure",
            "roots": sorted(roots),
            "mod_ids": sorted(selected),
            "artifacts": artifact_selection(records, selected),
            "missing_dependencies": missing,
        })
    if balanced_partitions:
        if balanced_partitions > len(all_ids):
            raise ValueError("balanced_partitions cannot exceed the number of mod ids")
        # Keep root assignment stable across runs.  Dependency closure is still
        # computed for each partition, so a required dependency may correctly
        # appear in more than one partition rather than being silently omitted.
        for index in range(balanced_partitions):
            roots = all_ids[index::balanced_partitions]
            runnable_roots = []
            excluded_roots = []
            for root in roots:
                _, root_missing = required_closure(
                    records,
                    [root],
                    parsed_compatibility_groups,
                    explicitly_excluded,
                )
                if root in explicitly_excluded:
                    excluded_roots.append({
                        "id": root,
                        "reason": "operator_excluded",
                        "missing_dependencies": [],
                    })
                elif root_missing:
                    excluded_roots.append({
                        "id": root,
                        "missing_dependencies": root_missing,
                    })
                else:
                    runnable_roots.append(root)
            selected, missing = required_closure(
                records,
                runnable_roots,
                parsed_compatibility_groups,
                explicitly_excluded,
            )
            if missing:
                raise ValueError(
                    f"balanced partition {index + 1} has an unexpected missing dependency closure: {missing}"
                )
            variants.append({
                "id": f"partition-{index + 1}",
                "kind": "balanced_partition",
                "roots": roots,
                "runnable_roots": runnable_roots,
                "excluded_roots": excluded_roots,
                "mod_ids": sorted(selected),
                "artifacts": artifact_selection(records, selected),
                "missing_dependencies": [],
            })

        # A subset partition is useful for locating broad attribution blocks,
        # but it is commonly not a runnable Minecraft pack: mods often have
        # undeclared runtime references to content outside their loader
        # dependency closure.  Emit complements as the safer follow-up: they
        # retain most of the real pack and remove only one deterministic block.
        # These are selected explicitly by the workflow (they do not alter the
        # existing partition IDs or their diagnostic-only semantics).
        for index in range(balanced_partitions):
            assigned = set(all_ids[index::balanced_partitions])
            excluded = expand_compatibility_exclusions(
                assigned | explicitly_excluded,
                parsed_compatibility_groups,
            )
            candidate_roots = [mod_id for mod_id in all_ids if mod_id not in excluded]
            runnable_roots = []
            excluded_records = []
            for root in sorted(assigned | explicitly_excluded):
                reason = "operator_excluded" if root in explicitly_excluded else "complement_excluded"
                excluded_records.append({
                    "id": root,
                    "reason": reason,
                    "missing_dependencies": [],
                })
            for group in parsed_compatibility_groups:
                if assigned.intersection(group):
                    for root in sorted(set(group) - assigned - explicitly_excluded):
                        excluded_records.append({
                            "id": root,
                            "reason": "compatibility_group_excluded",
                            "missing_dependencies": [],
                        })
            for root in candidate_roots:
                _, root_missing = required_closure(
                    records,
                    [root],
                    parsed_compatibility_groups,
                    excluded,
                )
                if root_missing:
                    excluded_records.append({
                        "id": root,
                        "reason": "depends_on_excluded_or_missing",
                        "missing_dependencies": root_missing,
                    })
                else:
                    runnable_roots.append(root)
            selected, missing = required_closure(
                records,
                runnable_roots,
                parsed_compatibility_groups,
                excluded,
            )
            if missing:
                raise ValueError(
                    f"balanced complement {index + 1} has an unexpected missing dependency closure: {missing}"
                )
            variants.append({
                "id": f"complement-{index + 1}",
                "kind": "balanced_complement",
                "roots": sorted(candidate_roots),
                "runnable_roots": sorted(runnable_roots),
                "excluded_roots": sorted(
                    excluded_records,
                    key=lambda item: (item["id"], item["reason"]),
                ),
                "mod_ids": sorted(selected),
                "artifacts": artifact_selection(records, selected),
                "missing_dependencies": [],
            })

    return {
        "schema": 1,
        "pack_directory": str(pack_dir.resolve()),
        "pack_fingerprint": fingerprint,
        "mod_count": len(all_ids),
        "compatibility_groups": [
            {"name": raw.split("=", 1)[0].strip(), "mod_ids": sorted(values)}
            for raw, values in zip(compatibility_groups or [], parsed_compatibility_groups)
        ],
        "explicitly_excluded_roots": sorted(explicitly_excluded),
        "mods": [
            {
                "id": mod_id,
                "artifacts": sorted({record.artifact for record in records[mod_id]}, key=str.lower),
                "versions": sorted({record.version for record in records[mod_id] if record.version}),
                "required_dependencies": sorted({
                    dependency
                    for record in records[mod_id]
                    for dependency in record.dependencies.get(mod_id, set())
                }),
                "optional_dependencies": sorted({
                    dependency
                    for record in records[mod_id]
                    for dependency in record.optional_dependencies.get(mod_id, set())
                }),
            }
            for mod_id in all_ids
        ],
        "variants": variants,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", action="append", default=[], help="Required baseline mod id; repeatable")
    parser.add_argument("--group", action="append", default=[], help="NAME=mod1,mod2 interaction group; repeatable")
    parser.add_argument(
        "--balanced-partitions",
        type=int,
        default=0,
        help="Add deterministic round-robin root partitions; repeatable closures expose broad scaling blocks",
    )
    parser.add_argument(
        "--compatibility-group",
        action="append",
        default=[],
        help="Runtime family NAME=mod1,mod2 whose members must stay together in closures",
    )
    parser.add_argument(
        "--exclude-root",
        action="append",
        default=[],
        help="Root to omit from balanced partitions when the host cannot run it; exclusion is recorded",
    )
    args = parser.parse_args()
    try:
        plan = build_plan(
            args.pack_dir,
            args.group,
            args.baseline,
            args.balanced_partitions,
            args.compatibility_group,
            args.exclude_root,
        )
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from error
    serialized = json.dumps(plan, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    else:
        print(serialized, end="")


if __name__ == "__main__":
    main()
