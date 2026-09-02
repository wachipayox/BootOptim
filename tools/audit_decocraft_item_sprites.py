#!/usr/bin/env python3
"""Static audit for Decocraft 3.0.11 item textures/models.

Research-only helper. Downloads the exact public Modrinth release, inspects the JAR
without extracting it, and emits aggregate counts plus the exact item->texture
mapping used by the guarded 3D-item experiment.
"""

from __future__ import annotations

import collections
import io
import json
import struct
import urllib.request
import zipfile

VERSION_ID = "Z8xm2POI"
PROJECT_ID = "IZJSgKZe"
FILENAME = "decocraft-3.0.11-1.21.1-neoforge.jar"
URL = f"https://cdn.modrinth.com/data/{PROJECT_ID}/versions/{VERSION_ID}/{FILENAME}"
NS = "decocraft"
ITEM_MODEL_PREFIX = f"assets/{NS}/models/item/"
BLOCK_MODEL_PREFIX = f"assets/{NS}/models/block/"
ITEM_TEXTURE_PREFIX = f"assets/{NS}/textures/item/"
ATLAS_PREFIXES = (f"assets/{NS}/atlases/", "assets/minecraft/atlases/")
GENERATED_PARENTS = {"minecraft:item/generated", "item/generated", "builtin/generated"}


def load_json(zf: zipfile.ZipFile, name: str):
    try:
        return json.loads(zf.read(name).decode("utf-8"))
    except Exception:
        return None


def png_size(data: bytes):
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def model_id_from_path(path: str, prefix: str) -> str:
    rel = path[len(prefix):]
    return rel[:-5] if rel.endswith(".json") else rel


def flatten_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from flatten_strings(v)
    elif isinstance(value, list):
        for v in value:
            yield from flatten_strings(v)


def main() -> None:
    req = urllib.request.Request(URL, headers={"User-Agent": "BootOptim research audit/1"})
    with urllib.request.urlopen(req, timeout=120) as response:
        blob = response.read()

    zf = zipfile.ZipFile(io.BytesIO(blob))
    names = set(zf.namelist())

    item_models = sorted(n for n in names if n.startswith(ITEM_MODEL_PREFIX) and n.endswith(".json"))
    block_models = sorted(n for n in names if n.startswith(BLOCK_MODEL_PREFIX) and n.endswith(".json"))
    item_textures = sorted(n for n in names if n.startswith(ITEM_TEXTURE_PREFIX) and n.endswith(".png"))
    all_json = sorted(n for n in names if n.endswith(".json") and n.startswith("assets/"))

    parsed_json = {name: load_json(zf, name) for name in all_json}
    block_ids = {model_id_from_path(n, BLOCK_MODEL_PREFIX) for n in block_models}

    # Count exact resource-id references in every asset JSON. This lets us identify
    # textures that are only referenced by the generated item model that owns them.
    texture_referrers: dict[str, set[str]] = collections.defaultdict(set)
    for path, obj in parsed_json.items():
        if obj is None:
            continue
        for value in flatten_strings(obj):
            if value.startswith(f"{NS}:item/"):
                texture_referrers[value].add(path)

    stats = collections.Counter()
    candidate_textures: set[str] = set()
    generated_textures: set[str] = set()
    same_name_candidates: list[tuple[str, str]] = []
    no_other_ref_candidates: list[tuple[str, str]] = []
    custom_block_candidates = 0

    for path in item_models:
        stats["item_models"] += 1
        obj = parsed_json.get(path)
        if not isinstance(obj, dict):
            stats["item_models_unparseable"] += 1
            continue
        item_id = model_id_from_path(path, ITEM_MODEL_PREFIX)
        parent = obj.get("parent")
        if parent in GENERATED_PARENTS:
            stats["generated_parent"] += 1
        else:
            continue

        textures = obj.get("textures")
        if not isinstance(textures, dict):
            continue
        layer0 = textures.get("layer0")
        if not isinstance(layer0, str) or not layer0.startswith(f"{NS}:item/"):
            continue
        stats["generated_decocraft_layer0"] += 1
        generated_textures.add(layer0)

        tex_rel = layer0.split(":", 1)[1][len("item/"):]
        tex_path = f"{ITEM_TEXTURE_PREFIX}{tex_rel}.png"
        if tex_path in names:
            stats["generated_layer0_texture_exists"] += 1
        else:
            continue

        if tex_rel == item_id:
            stats["generated_texture_same_name"] += 1
        if item_id in block_ids:
            stats["same_name_block_model_exists"] += 1
        else:
            continue

        extra_keys = set(obj) - {"parent", "textures"}
        only_layer0 = set(textures) <= {"layer0", "particle"}
        if not extra_keys and only_layer0:
            stats["pure_generated_same_name_block"] += 1
            candidate_textures.add(layer0)
            same_name_candidates.append((item_id, layer0))

            refs = texture_referrers.get(layer0, set())
            if refs <= {path}:
                stats["pure_candidate_no_other_json_ref"] += 1
                no_other_ref_candidates.append((item_id, layer0))

            block_obj = parsed_json.get(f"{BLOCK_MODEL_PREFIX}{item_id}.json")
            if isinstance(block_obj, dict) and (
                "loader" in block_obj
                or "neoforge:loader" in block_obj
                or "geometry" in block_obj
                or any("loader" in str(k).lower() for k in block_obj)
            ):
                custom_block_candidates += 1

    # Encoded/decoded footprint of exact item texture corpus and candidate subsets.
    def footprint(resource_ids: set[str] | None):
        encoded = compressed = decoded = count = 0
        for path in item_textures:
            rid = f"{NS}:item/{path[len(ITEM_TEXTURE_PREFIX):-4]}"
            if resource_ids is not None and rid not in resource_ids:
                continue
            info = zf.getinfo(path)
            data = zf.read(path)
            dims = png_size(data)
            count += 1
            encoded += info.file_size
            compressed += info.compress_size
            if dims:
                decoded += dims[0] * dims[1] * 4
        return count, encoded, compressed, decoded

    all_fp = footprint(None)
    generated_fp = footprint(generated_textures)
    candidate_fp = footprint(candidate_textures)
    no_other_ref_fp = footprint({tex for _, tex in no_other_ref_candidates})

    atlas_files = [n for n in sorted(names) if n.endswith(".json") and n.startswith(ATLAS_PREFIXES)]
    atlas_directory_item_mentions = []
    for n in atlas_files:
        obj = parsed_json.get(n) or load_json(zf, n)
        text = json.dumps(obj, sort_keys=True) if obj is not None else ""
        if '"source": "item"' in text or '"source":"item"' in text:
            atlas_directory_item_mentions.append(n)

    lines = []
    lines.append("# Decocraft 3.0.11 item-sprite static audit")
    lines.append("")
    lines.append(f"Artifact: `{FILENAME}` / Modrinth version `{VERSION_ID}`")
    lines.append(f"JAR bytes: `{len(blob)}`")
    lines.append("")
    lines.append("## Model mapping")
    for key in [
        "item_models",
        "item_models_unparseable",
        "generated_parent",
        "generated_decocraft_layer0",
        "generated_layer0_texture_exists",
        "generated_texture_same_name",
        "same_name_block_model_exists",
        "pure_generated_same_name_block",
        "pure_candidate_no_other_json_ref",
    ]:
        lines.append(f"- `{key}`: **{stats[key]}**")
    lines.append(f"- pure candidates whose same-name block JSON advertises a custom/loader-like key: **{custom_block_candidates}**")
    lines.append("")
    lines.append("`pure_generated_same_name_block` means the item JSON contains only a generated parent + texture map, its layer0 PNG exists, and a same-path block model exists. It is a static candidate count, not yet a semantic-equivalence claim.")
    lines.append("")
    lines.append("## Texture footprint")
    lines.append("")
    lines.append("| subset | PNG count | PNG bytes | outer ZIP bytes | decoded RGBA bytes |")
    lines.append("| --- | ---: | ---: | ---: | ---: |")
    for label, fp in [
        ("all item textures", all_fp),
        ("referenced by generated item models", generated_fp),
        ("pure generated + same-name block", candidate_fp),
        ("pure candidate + no other asset-JSON reference", no_other_ref_fp),
    ]:
        lines.append(f"| {label} | {fp[0]} | {fp[1]} | {fp[2]} | {fp[3]} |")
    lines.append("")
    lines.append("## Atlas declarations inside Decocraft JAR")
    lines.append(f"- atlas JSON files found: **{len(atlas_files)}**")
    lines.append(f"- atlas JSONs with an explicit directory `source: item`: **{len(atlas_directory_item_mentions)}**")
    if atlas_files:
        for n in atlas_files[:20]:
            lines.append(f"  - `{n}`")
    lines.append("")
    lines.append("Absence of a Decocraft-local `source: item` does not prove the sprites are demand-driven: Minecraft's blocks atlas can enumerate `textures/item` across namespaces. Runtime PR #72 loaded 5,771/5,773 Decocraft texture PNGs, so any production experiment must verify that the atlas supplier set itself shrinks; replacing item model JSON alone is not enough.")
    lines.append("")
    lines.append("## Sample mechanically clean candidates")
    for item_id, tex in no_other_ref_candidates[:40]:
        lines.append(f"- `{item_id}` -> `{tex}` -> `decocraft:block/{item_id}`")
    lines.append("")
    lines.append("Samples are only identifiers for follow-up source inspection; no third-party asset contents are reproduced.")

    report = "\n".join(lines) + "\n"
    print(report)
    with open("decocraft-item-sprite-audit.md", "w", encoding="utf-8") as out:
        out.write(report)

    # This is the exact static allowlist consumed by the runtime experiment. Keep the
    # item id and texture id separate: a small number of valid candidates do not use
    # a same-name texture. No asset bytes are copied, only public resource identifiers.
    with open("decocraft-3d-item-candidates.txt", "w", encoding="utf-8", newline="\n") as out:
        out.write("# Decocraft 3.0.11 / Modrinth Z8xm2POI\n")
        out.write("# item_model_path<TAB>item_texture_resource_location\n")
        for item_id, texture in sorted(no_other_ref_candidates):
            out.write(f"{item_id}\t{texture}\n")


if __name__ == "__main__":
    main()
