#!/usr/bin/env python3
"""Build a replay fixture from the model/blockstate resources in an exact pack.

This intentionally models resource/model dependency structure and relative work,
not Minecraft's complete rendering semantics. It is a cheap design laboratory:
stock-equivalent output and real startup timings still require later gates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import posixpath
import re
import zipfile
from pathlib import Path
from typing import Any, Iterable, TextIO


SCHEMA = 1


def logical_id(path: str) -> tuple[str, str] | None:
    parts = path.replace("\\", "/").split("/")
    if len(parts) < 4 or parts[0] != "assets" or not parts[1]:
        return None
    namespace = parts[1]
    if parts[2] == "models" and path.endswith(".json"):
        return "model", f"{namespace}:{posixpath.splitext('/'.join(parts[3:]))[0]}"
    if parts[2] == "blockstates" and path.endswith(".json"):
        return "blockstate", f"{namespace}:{posixpath.splitext('/'.join(parts[3:]))[0]}"
    return None


def normalize_reference(reference: Any, namespace: str) -> str | None:
    if not isinstance(reference, str) or not reference:
        return None
    if ":" in reference:
        ref_namespace, ref_path = reference.split(":", 1)
    else:
        ref_namespace, ref_path = namespace, reference
    if ref_path.endswith(".json"):
        ref_path = ref_path[:-5]
    return f"{ref_namespace}:{ref_path}"


def resource_names(pack_root: Path) -> list[tuple[str, str, bytes]]:
    """Return (source, entry, content) in a stable source order."""
    result: list[tuple[str, str, bytes]] = []
    direct_files = sorted(
        path for path in pack_root.rglob("*.json")
        if any(part in {"models", "blockstates"} for part in path.parts)
        and "assets" in path.parts
    )
    for path in direct_files:
        relative = path.relative_to(pack_root).as_posix()
        if logical_id(relative):
            result.append(("pack-root", relative, path.read_bytes()))

    archives = sorted(
        path for directory in (pack_root / "mods", pack_root / "resourcepacks")
        if directory.is_dir()
        for path in directory.iterdir()
        if path.is_file() and path.suffix.lower() in {".jar", ".zip"}
    )
    for archive in archives:
        try:
            with zipfile.ZipFile(archive) as handle:
                for info in sorted(handle.infolist(), key=lambda item: item.filename):
                    if info.is_dir() or not logical_id(info.filename):
                        continue
                    result.append((archive.name, info.filename, handle.read(info)))
        except (OSError, zipfile.BadZipFile):
            # A broken/unsupported optional source is recorded in the manifest by
            # its omission; the real exact-pack gate remains responsible for it.
            continue
    return result


def find_models(value: Any, namespace: str) -> Iterable[str]:
    if isinstance(value, dict):
        model = value.get("model")
        normalized = normalize_reference(model, namespace)
        if normalized:
            yield normalized
        for child in value.values():
            yield from find_models(child, namespace)
    elif isinstance(value, list):
        for child in value:
            yield from find_models(child, namespace)


def model_metrics(value: Any) -> tuple[int, int, int, int]:
    if not isinstance(value, dict):
        return 0, 0, 0, 0
    elements = value.get("elements")
    textures = value.get("textures")
    overrides = value.get("overrides")
    return (
        len(elements) if isinstance(elements, list) else 0,
        len(textures) if isinstance(textures, dict) else 0,
        len(overrides) if isinstance(overrides, list) else 0,
        1 if value.get("parent") else 0,
    )


def break_cycles(tasks: dict[str, dict[str, Any]]) -> int:
    visiting: set[str] = set()
    visited: set[str] = set()
    removed = 0

    def visit(task_id: str) -> None:
        nonlocal removed
        if task_id in visited:
            return
        if task_id in visiting:
            return
        visiting.add(task_id)
        dependencies = list(tasks[task_id]["depends_on"])
        for dependency in dependencies:
            if dependency not in tasks:
                tasks[task_id]["depends_on"].remove(dependency)
                continue
            if dependency in visiting:
                tasks[task_id]["depends_on"].remove(dependency)
                removed += 1
            else:
                visit(dependency)
        visiting.remove(task_id)
        visited.add(task_id)

    for task_id in sorted(tasks):
        visit(task_id)
    return removed


def build_fixture(pack_root: Path) -> dict[str, Any]:
    entries = resource_names(pack_root)
    selected: dict[str, dict[str, Any]] = {}
    shadowed_resources = 0
    source_digest = hashlib.sha256()
    for source, entry, content in entries:
        parsed_id = logical_id(entry)
        if not parsed_id:
            continue
        kind, resource_id = parsed_id
        namespace = resource_id.split(":", 1)[0]
        try:
            value = json.loads(content.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        source_digest.update(source.encode("utf-8"))
        source_digest.update(b"\0")
        source_digest.update(entry.encode("utf-8"))
        source_digest.update(b"\0")
        source_digest.update(content)
        if resource_id in selected:
            shadowed_resources += 1
        selected[resource_id] = {
            "kind": kind,
            "namespace": namespace,
            "source": source,
            "entry": entry,
            "value": value,
            "size": len(content),
        }

    tasks: dict[str, dict[str, Any]] = {
        "enumerate:model": {
            "id": "enumerate:model",
            "kind": "model_enumeration",
            "source": "pack-root",
            "entry": "assets/*/models/**/*.json",
            "duration_ms": round(0.5 + sum(item["kind"] == "model" for item in selected.values()) * 0.02, 6),
            "depends_on": [],
            "order": 0,
        },
        "enumerate:blockstate": {
            "id": "enumerate:blockstate",
            "kind": "blockstate_enumeration",
            "source": "pack-root",
            "entry": "assets/*/blockstates/*.json",
            "duration_ms": round(0.5 + sum(item["kind"] == "blockstate" for item in selected.values()) * 0.02, 6),
            "depends_on": [],
            "order": 1,
        },
    }
    for index, resource_id in enumerate(sorted(selected)):
        item = selected[resource_id]
        value = item["value"]
        if item["kind"] == "model":
            elements, textures, overrides, _ = model_metrics(value)
            parent = normalize_reference(value.get("parent"), item["namespace"])
            dependencies = ["enumerate:model"]
            if parent in selected:
                dependencies.append(f"model:{parent}")
            # These are relative work units, intentionally not milliseconds. The
            # graph is calibrated later with real profiler totals if needed.
            work = 1.0 + item["size"] / 1024.0 + elements * 0.5 + textures * 0.1 + overrides * 0.2
            task_id = f"model:{resource_id}"
            tasks[task_id] = {
                "id": task_id,
                "kind": "model_parse",
                "source": item["source"],
                "entry": item["entry"],
                "duration_ms": round(work, 6),
                "depends_on": dependencies,
                "order": index + 10,
            }
            bake_dependencies = [task_id]
            if parent in selected:
                bake_dependencies.append(f"bake:{parent}")
            tasks[f"bake:{resource_id}"] = {
                "id": f"bake:{resource_id}",
                "kind": "model_bake",
                "source": item["source"],
                "entry": item["entry"],
                "duration_ms": round(max(0.5, work * 0.8 + elements * 0.3), 6),
                "depends_on": bake_dependencies,
                "order": index + 10_000,
            }
        else:
            references = sorted(set(find_models(value, item["namespace"])))
            dependencies = ["enumerate:blockstate"]
            dependencies.extend(f"model:{reference}" for reference in references if reference in selected)
            variant_count = sum(1 for _ in find_models(value, item["namespace"]))
            work = 1.0 + item["size"] / 1024.0 + variant_count * 0.2
            task_id = f"blockstate:{resource_id}"
            tasks[task_id] = {
                "id": task_id,
                "kind": "blockstate_parse",
                "source": item["source"],
                "entry": item["entry"],
                "duration_ms": round(work, 6),
                "depends_on": dependencies,
                "order": index + 20_000,
            }

    cycle_edges_removed = break_cycles(tasks)
    manifest_hash = hashlib.sha256()
    manifest_hash.update(source_digest.digest())
    manifest_hash.update(json.dumps(tasks, sort_keys=True).encode("utf-8"))
    counts = {
        "models": sum(item["kind"] == "model" for item in selected.values()),
        "blockstates": sum(item["kind"] == "blockstate" for item in selected.values()),
    }
    options_path = pack_root / "options.txt"
    options_text = options_path.read_text(encoding="utf-8", errors="replace") if options_path.is_file() else ""
    resource_pack_line = next(
        (line for line in options_text.splitlines() if line.startswith("resourcePacks:")),
        "",
    )
    selected_resource_packs = re.findall(r'"([^"]+)"', resource_pack_line)
    options_hash = hashlib.sha256(options_text.encode("utf-8")).hexdigest() if options_text else None
    return {
        "schema": SCHEMA,
        "fixture_id": f"exact-pack-model-graph-{manifest_hash.hexdigest()[:16]}",
        "variant": "pack-graph",
        "metadata": {
            "origin": "exact_pack_extract",
            "endpoint": "model_blockstate_dependency_graph",
            "pack_root": str(pack_root),
            "duration_semantics": "relative_model_complexity_work_units",
            "resource_entries_considered": len(entries),
            "logical_resources_selected": len(selected),
            "shadowed_logical_resources": shadowed_resources,
            "options_sha256": options_hash,
            "selected_resource_packs": selected_resource_packs,
            "cycle_edges_removed": cycle_edges_removed,
            **counts,
        },
        "tasks": list(tasks.values()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixture = build_fixture(args.pack_root.resolve())
    args.output.write_text(json.dumps(fixture, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"fixture_id": fixture["fixture_id"], **fixture["metadata"]}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
