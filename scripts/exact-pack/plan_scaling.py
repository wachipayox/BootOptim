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


def required_closure(records: dict[str, list[ModRecord]], roots: list[str]) -> tuple[set[str], list[str]]:
    selected: set[str] = set()
    missing: set[str] = set()
    pending = list(roots)
    while pending:
        mod_id = pending.pop()
        if mod_id in PLATFORM_PROVIDED_MOD_IDS:
            continue
        if mod_id in selected:
            continue
        matching_records = records.get(mod_id)
        if not matching_records:
            missing.add(mod_id)
            continue
        selected.add(mod_id)
        dependencies = set()
        for record in matching_records:
            dependencies.update(record.dependencies.get(mod_id, set()))
            dependencies.update(
                dependency
                for dependency in record.optional_dependencies.get(mod_id, set())
                if dependency in records
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


def build_plan(
    pack_dir: Path,
    groups: list[str],
    baseline: list[str],
    balanced_partitions: int = 0,
) -> dict:
    if balanced_partitions < 0:
        raise ValueError("balanced_partitions must not be negative")
    records, artifact_to_ids, fingerprint = scan_pack(pack_dir.resolve())
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

    baseline_ids, baseline_missing = required_closure(records, baseline)
    variants.append({
        "id": "baseline",
        "kind": "baseline",
        "roots": sorted(baseline),
        "mod_ids": sorted(baseline_ids),
        "artifacts": artifact_selection(records, baseline_ids),
        "missing_dependencies": baseline_missing,
    })

    for mod_id in all_ids:
        selected, missing = required_closure(records, [mod_id])
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
        selected, missing = required_closure(records, roots)
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
                _, root_missing = required_closure(records, [root])
                if root_missing:
                    excluded_roots.append({
                        "id": root,
                        "missing_dependencies": root_missing,
                    })
                else:
                    runnable_roots.append(root)
            selected, missing = required_closure(records, runnable_roots)
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

    return {
        "schema": 1,
        "pack_directory": str(pack_dir.resolve()),
        "pack_fingerprint": fingerprint,
        "mod_count": len(all_ids),
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
    args = parser.parse_args()
    try:
        plan = build_plan(args.pack_dir, args.group, args.baseline, args.balanced_partitions)
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
