#!/usr/bin/env python3
"""Prepare a bounded model-resource fixture for the real Java class replay.

This file is deliberately *not* the model algorithm.  It only enumerates archive
resources, applies the enabled external resource-pack stack from options.txt,
keeps provenance/shadowed entries, and materializes bytes for the Java runner.
The measured parser/parent/material work is performed by Minecraft/NeoForge
classes in ModelClassReplay.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

EXACT_PACK_SHA256 = "7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639"
SCHEMA = 1
MODEL_RE = re.compile(r"^assets/([^/]+)/models/(.+)\.json$")


@dataclass(frozen=True)
class Candidate:
    resource_id: str
    source_id: str
    source_kind: str
    source_path: str
    entry: str
    priority: int
    content: bytes
    precedence_authority: str


def _model_id(entry: str) -> str | None:
    match = MODEL_RE.match(entry.replace("\\", "/"))
    return f"{match.group(1)}:{match.group(2)}" if match else None


def _resource_packs(options: str) -> list[str]:
    line = next((line for line in options.splitlines() if line.startswith("resourcePacks:")), None)
    if not line:
        return []
    try:
        values = json.loads(line.split(":", 1)[1])
    except json.JSONDecodeError:
        return []
    result: list[str] = []
    for value in values if isinstance(values, list) else []:
        if isinstance(value, str) and value.startswith("file/"):
            result.append(value[5:])
    return result


def _archive_entries(path: Path, source_id: str, source_kind: str, priority: int,
                     authority: str) -> Iterable[Candidate]:
    try:
        with zipfile.ZipFile(path) as archive:
            # ZipFile.getinfo semantics for duplicate names are implementation-defined enough
            # that the fixture refuses duplicate model paths rather than inventing precedence.
            seen: set[str] = set()
            duplicate: set[str] = set()
            infos = [info for info in archive.infolist() if not info.is_dir() and _model_id(info.filename)]
            for info in infos:
                if info.filename in seen:
                    duplicate.add(info.filename)
                seen.add(info.filename)
            for info in infos:
                if info.filename in duplicate:
                    continue
                resource_id = _model_id(info.filename)
                assert resource_id is not None
                yield Candidate(resource_id, source_id, source_kind, str(path), info.filename,
                                priority, archive.read(info), authority)
    except (OSError, zipfile.BadZipFile):
        return


def _directory_entries(path: Path, source_id: str, source_kind: str, priority: int,
                       authority: str) -> Iterable[Candidate]:
    for file in sorted(path.rglob("*.json")):
        try:
            entry = file.relative_to(path).as_posix()
        except ValueError:
            continue
        resource_id = _model_id(entry)
        if resource_id:
            yield Candidate(resource_id, source_id, source_kind, str(path), entry,
                            priority, file.read_bytes(), authority)


def enumerate_candidates(pack_root: Path) -> tuple[list[Candidate], dict]:
    options_path = pack_root / "options.txt"
    options = options_path.read_text(encoding="utf-8", errors="replace") if options_path.is_file() else ""
    selected_external = _resource_packs(options)
    candidates: list[Candidate] = []
    stack: list[dict] = []
    priority = 0

    # NeoForge mod resources are below user-selected resource packs.  Their exact
    # mod-to-mod order is not reconstructed here; winners from this tier are marked
    # accordingly and are not preferred for the bounded semantic sample.
    mods = pack_root / "mods"
    if mods.is_dir():
        for path in sorted(p for p in mods.iterdir() if p.is_file() and p.suffix.lower() == ".jar"):
            source_id = f"mod-archive:{path.name}"
            stack.append({"source_id": source_id, "kind": "mod_archive", "priority": priority,
                          "precedence_authority": "bounded_helper_mod_filename_order"})
            candidates.extend(_archive_entries(path, source_id, "mod_archive", priority,
                                               "bounded_helper_mod_filename_order"))
            priority += 1

    resourcepacks = pack_root / "resourcepacks"
    by_name = {p.name: p for p in resourcepacks.iterdir()} if resourcepacks.is_dir() else {}
    missing_selected: list[str] = []
    for name in selected_external:
        path = by_name.get(name)
        if path is None:
            missing_selected.append(name)
            continue
        source_id = f"external-pack:{name}"
        stack.append({"source_id": source_id, "kind": "external_resource_pack", "priority": priority,
                      "precedence_authority": "options.txt selected order; later wins"})
        if path.is_dir():
            candidates.extend(_directory_entries(path, source_id, "external_resource_pack", priority,
                                                  "options.txt selected order; later wins"))
        elif path.suffix.lower() in {".zip", ".jar"}:
            candidates.extend(_archive_entries(path, source_id, "external_resource_pack", priority,
                                                "options.txt selected order; later wins"))
        priority += 1

    metadata = {
        "options_sha256": hashlib.sha256(options.encode()).hexdigest() if options else None,
        "selected_external_resource_packs_low_to_high": selected_external,
        "missing_selected_external_packs": missing_selected,
        "resource_stack_low_to_high": stack,
        "builtin_minecraft_fallback": "Java classpath, resolved by the class replay",
    }
    return candidates, metadata


def _safe_json(content: bytes) -> dict | None:
    try:
        value = json.loads(content.decode("utf-8"))
        return value if isinstance(value, dict) else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def _parent(value: dict, namespace: str) -> str | None:
    raw = value.get("parent")
    if not isinstance(raw, str) or not raw:
        return None
    if ":" in raw:
        return raw.removesuffix(".json")
    return f"{namespace}:{raw.removesuffix('.json')}"


def _features(value: dict) -> set[str]:
    result: set[str] = set()
    if isinstance(value.get("parent"), str): result.add("parent")
    if isinstance(value.get("textures"), dict) and value["textures"]: result.add("textures")
    elements = value.get("elements")
    if isinstance(elements, list) and elements:
        result.add("elements")
        if any(isinstance(e, dict) and e.get("rotation") is not None for e in elements): result.add("element_rotation")
        if any(isinstance(e, dict) and any(isinstance(f, dict) and f.get("cullface") is not None
                   for f in (e.get("faces") or {}).values()) for e in elements): result.add("cull")
    if isinstance(value.get("display"), dict) and value["display"]: result.add("display_transform")
    return result


def build(pack_root: Path, output_dir: Path, limit: int, full: bool) -> dict:
    candidates, metadata = enumerate_candidates(pack_root)
    grouped: dict[str, list[Candidate]] = {}
    for candidate in candidates:
        grouped.setdefault(candidate.resource_id, []).append(candidate)
    for values in grouped.values():
        values.sort(key=lambda item: (item.priority, item.source_id, item.entry))

    winners = {rid: values[-1] for rid, values in grouped.items()}
    parsed = {rid: _safe_json(candidate.content) for rid, candidate in winners.items()}
    parsed = {rid: value for rid, value in parsed.items() if value is not None}

    if full:
        roots = sorted(parsed)
    else:
        ranked = []
        for rid, value in parsed.items():
            candidate = winners[rid]
            features = _features(value)
            score = len(features) * 10 + len(candidate.content) / 4096.0
            # Exact options.txt precedence is strongest for user resource-pack winners.
            external_bonus = 100 if candidate.source_kind == "external_resource_pack" else 0
            ranked.append((-(external_bonus + score), rid, features))
        ranked.sort(key=lambda item: (item[0], item[1]))
        chosen: list[str] = []
        covered: set[str] = set()
        wanted = {"parent", "textures", "elements", "element_rotation", "cull", "display_transform"}
        for _, rid, features in ranked:
            if features - covered:
                chosen.append(rid); covered |= features
                if wanted <= covered and len(chosen) >= min(6, limit): break
        for _, rid, _ in ranked:
            if len(chosen) >= limit: break
            if rid not in chosen: chosen.append(rid)
        roots = chosen[:limit]

    # Materialize the parent closure so the Java resolver, not this helper, performs
    # parent resolution. Reading the raw `parent` string here only bounds fixture bytes.
    closure = set(roots)
    queue = list(roots)
    while queue:
        rid = queue.pop()
        value = parsed.get(rid)
        if not value: continue
        namespace = rid.split(":", 1)[0]
        parent = _parent(value, namespace)
        if parent in parsed and parent not in closure:
            closure.add(parent); queue.append(parent)

    if output_dir.exists(): shutil.rmtree(output_dir)
    resources_dir = output_dir / "resources"
    resources_dir.mkdir(parents=True)
    entries = []
    for rid in sorted(closure):
        winner = winners[rid]
        digest = hashlib.sha256(winner.content).hexdigest()
        path = resources_dir / f"{digest}.json"
        path.write_bytes(winner.content)
        shadows = grouped[rid][:-1]
        entries.append({
            "id": rid,
            "content_path": str(path.relative_to(output_dir)),
            "sha256": digest,
            "winner": {"source_id": winner.source_id, "source_kind": winner.source_kind,
                       "source_path": winner.source_path, "entry": winner.entry,
                       "priority": winner.priority,
                       "precedence_authority": winner.precedence_authority},
            "shadowed": [{"source_id": c.source_id, "source_kind": c.source_kind,
                          "source_path": c.source_path, "entry": c.entry, "priority": c.priority}
                         for c in shadows],
        })

    manifest = {
        "schema": SCHEMA,
        "origin": "exact-pack public release fixture",
        "exact_pack_sha256": EXACT_PACK_SHA256,
        "selection_scope": "all winning model resources" if full else "bounded representative parent closure",
        "roots": roots,
        "entries": entries,
        "counts": {"candidates": len(candidates), "logical_models": len(grouped),
                   "materialized_parent_closure": len(entries), "roots": len(roots),
                   "shadowed_entries": sum(max(0, len(v) - 1) for v in grouped.values())},
        **metadata,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=24)
    parser.add_argument("--full", action="store_true")
    args = parser.parse_args()
    manifest = build(args.pack_root.resolve(), args.output_dir.resolve(), max(1, args.limit), args.full)
    print(json.dumps({"roots": manifest["roots"], "counts": manifest["counts"],
                      "selected_external_resource_packs_low_to_high": manifest["selected_external_resource_packs_low_to_high"]},
                     indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
