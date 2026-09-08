#!/usr/bin/env python3
"""Build a reproducible mod-scaling plan from an extracted exact-pack directory.

The planner is intentionally read-only. It never edits the fixture and does not launch Minecraft.
Each generated variant selects a complete transitive closure of required mod dependencies so a later
stager can create an isolated copy without silently dropping a loader contract.
"""

from __future__ import annotations

import argparse
import hashlib
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


def read_metadata(jar: Path) -> tuple[list[tuple[str, str | None]], dict[str, set[str]]]:
    metadata_name = None
    with zipfile.ZipFile(jar) as archive:
        for candidate in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if candidate in archive.namelist():
                metadata_name = candidate
                break
        if metadata_name is None:
            return [], {}
        text = archive.read(metadata_name).decode("utf-8", errors="replace")

    section = ""
    current_mod: str | None = None
    current_dependency: str | None = None
    mods: list[tuple[str, str | None]] = []
    dependencies: dict[str, set[str]] = {}
    dependency_mandatory = True

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
            dependency_mandatory = True
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
                dependencies.setdefault(current_mod or "", set())
                dependencies[current_mod or ""].add(value)
            elif key == "mandatory":
                dependency_mandatory = raw_value.lower() == "true"
                if not dependency_mandatory and current_mod and current_dependency:
                    dependencies.setdefault(current_mod, set()).discard(current_dependency)
    return mods, dependencies


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
        mod_entries, dependencies = read_metadata(jar)
        if not mod_entries:
            mod_id = jar.stem.lower()
            mod_entries = [(mod_id, None)]
        mod_ids = [entry[0] for entry in mod_entries]
        version = next((entry[1] for entry in mod_entries if entry[1]), None)
        record = ModRecord(jar.name, mod_ids, version, dependencies, sha256(jar))
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


def build_plan(pack_dir: Path, groups: list[str], baseline: list[str]) -> dict:
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
    args = parser.parse_args()
    try:
        plan = build_plan(args.pack_dir, args.group, args.baseline)
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
