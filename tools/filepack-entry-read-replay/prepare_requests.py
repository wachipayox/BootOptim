#!/usr/bin/env python3
"""Build a FilePackResources ZIP I/O replay from the exact-pack and PR #183 contract.

The PR #183 bounded real-model manifest/replay is the selection/byte oracle for its
models. This extension uses the same selected external resource-pack order but covers
all valid `assets/<namespace>/<path>` entries so the expensive texture-heavy ZIPs are
not accidentally excluded. It does not parse model or texture semantics.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path

ASSET_RE = re.compile(r"^assets/([^/]+)/(.+)$")
VALID_NAMESPACE_RE = re.compile(r"^[a-z0-9_.-]+$")
VALID_PATH_RE = re.compile(r"^[a-z0-9/._-]+$")
EXPECTED_DIGEST = "502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050"
EXPECTED_EXACT_PACK = "7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639"


@dataclass(frozen=True)
class Candidate:
    resource_id: str
    pack_name: str
    archive: Path
    entry: str
    priority: int
    crc: int
    size: int
    compressed_size: int


def resource_id(entry: str) -> str | None:
    normalized = entry.replace("\\", "/")
    match = ASSET_RE.match(normalized)
    if not match:
        return None
    namespace, path = match.groups()
    # Match ResourceLocation's lowercase namespace/path acceptance closely enough for
    # this bounded replay; invalid asset names are counted and excluded rather than
    # silently converted.
    if not VALID_NAMESPACE_RE.fullmatch(namespace) or not VALID_PATH_RE.fullmatch(path):
        return None
    if "\t" in normalized or "\n" in normalized or "\r" in normalized:
        return None
    return f"{namespace}:{path}"


def model_contract_resource_id(model_id: str) -> str:
    namespace, path = model_id.split(":", 1)
    return f"{namespace}:models/{path}.json"


def selected_packs(options: str) -> list[str]:
    line = next((line for line in options.splitlines() if line.startswith("resourcePacks:")), None)
    if not line:
        return []
    try:
        values = json.loads(line.split(":", 1)[1])
    except json.JSONDecodeError:
        return []
    return [v[5:] for v in values if isinstance(v, str) and v.startswith("file/")]


def resolve_pack_root(extracted: Path) -> Path:
    if (extracted / "mods").is_dir():
        return extracted
    matches = sorted(p.parent for p in extracted.rglob("mods") if p.is_dir())
    if len(matches) != 1:
        raise RuntimeError(f"expected one extracted pack root, got {matches}")
    return matches[0]


def load_contract(artifact_root: Path) -> tuple[dict, dict]:
    manifest = json.loads((artifact_root / "model-replay-fixture" / "manifest.json").read_text())
    replay = json.loads((artifact_root / "model-replay-out" / "run1" / "replay.json").read_text())
    if manifest.get("exact_pack_sha256") != EXPECTED_EXACT_PACK:
        raise RuntimeError("PR #183 manifest exact-pack digest mismatch")
    if replay.get("semantic_sha256") != EXPECTED_DIGEST:
        raise RuntimeError("PR #183 semantic digest mismatch")
    if len(manifest.get("roots", [])) != 24 or len(manifest.get("entries", [])) != 24:
        raise RuntimeError("PR #183 bounded manifest shape changed")
    return manifest, replay


def enumerate_external(pack_root: Path, names: list[str]) -> tuple[list[Candidate], list[dict]]:
    resourcepacks = pack_root / "resourcepacks"
    by_name = {p.name: p for p in resourcepacks.iterdir()} if resourcepacks.is_dir() else {}
    candidates: list[Candidate] = []
    archives: list[dict] = []
    for priority, name in enumerate(names):
        path = by_name.get(name)
        if path is None:
            raise RuntimeError(f"selected external pack missing: {name}")
        if not path.is_file() or path.suffix.lower() not in {".zip", ".jar"}:
            continue
        try:
            with zipfile.ZipFile(path) as zf:
                all_infos = [i for i in zf.infolist() if not i.is_dir()]
                asset_infos = [i for i in all_infos if i.filename.replace("\\", "/").startswith("assets/")]
                counts: dict[str, int] = {}
                for info in asset_infos:
                    counts[info.filename] = counts.get(info.filename, 0) + 1
                duplicate_names = {entry for entry, count in counts.items() if count > 1}
                invalid_asset_entries = 0
                accepted = 0
                for info in asset_infos:
                    if info.filename in duplicate_names:
                        continue
                    logical = resource_id(info.filename)
                    if logical is None:
                        invalid_asset_entries += 1
                        continue
                    candidates.append(Candidate(logical, name, path.resolve(), info.filename,
                                                priority, info.CRC, info.file_size, info.compress_size))
                    accepted += 1
                archives.append({"pack": name, "path": str(path.resolve()),
                                 "central_entries": len(zf.infolist()),
                                 "asset_entries": len(asset_infos),
                                 "accepted_asset_entries": accepted,
                                 "invalid_asset_entries": invalid_asset_entries,
                                 "duplicate_asset_names_refused": len(duplicate_names),
                                 "size": path.stat().st_size,
                                 "mtime_ns": path.stat().st_mtime_ns})
        except (OSError, zipfile.BadZipFile) as exc:
            archives.append({"pack": name, "path": str(path.resolve()), "error": type(exc).__name__})
    return candidates, archives


def sha_for(path: Path, entry: str) -> str:
    with zipfile.ZipFile(path) as zf:
        return hashlib.sha256(zf.read(entry)).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack-extract", type=Path, required=True)
    ap.add_argument("--contract-artifact", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    args = ap.parse_args()

    pack_root = resolve_pack_root(args.pack_extract.resolve())
    contract, replay = load_contract(args.contract_artifact.resolve())
    options = (pack_root / "options.txt").read_text(encoding="utf-8", errors="replace")
    selected = selected_packs(options)
    if selected != contract.get("selected_external_resource_packs_low_to_high"):
        raise RuntimeError("selected external pack order differs from PR #183 contract")

    candidates, archives = enumerate_external(pack_root, selected)
    grouped: dict[str, list[Candidate]] = {}
    for c in candidates:
        grouped.setdefault(c.resource_id, []).append(c)
    for values in grouped.values():
        values.sort(key=lambda c: (c.priority, c.pack_name, c.entry))

    contract_sha_by_resource: dict[str, str] = {}
    contract_verified = 0
    for expected in contract["entries"]:
        logical = model_contract_resource_id(expected["id"])
        values = grouped.get(logical)
        if not values:
            raise RuntimeError(f"contract winner disappeared: {expected['id']} -> {logical}")
        winner = values[-1]
        exp_winner = expected["winner"]
        if exp_winner["source_id"] != f"external-pack:{winner.pack_name}" or exp_winner["entry"] != winner.entry:
            raise RuntimeError(f"winner provenance mismatch for {expected['id']}")
        if sha_for(winner.archive, winner.entry) != expected["sha256"]:
            raise RuntimeError(f"winner bytes mismatch for {expected['id']}")
        contract_sha_by_resource[logical] = expected["sha256"]
        contract_verified += 1

    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    tsv = out / "requests.tsv"
    winner_count = 0
    shadow_count = 0
    winner_uncompressed = 0
    winner_compressed = 0
    with tsv.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, delimiter="\t", lineterminator="\n")
        writer.writerow(["role", "logical_id", "pack", "archive", "entry", "priority",
                         "crc", "size", "compressed_size", "expected_sha256"])
        for logical in sorted(grouped):
            values = grouped[logical]
            winner = values[-1]
            writer.writerow(["winner", logical, winner.pack_name, winner.archive, winner.entry,
                             winner.priority, winner.crc, winner.size, winner.compressed_size,
                             contract_sha_by_resource.get(logical, "")])
            winner_count += 1
            winner_uncompressed += winner.size
            winner_compressed += winner.compressed_size
            for shadow in values[:-1]:
                writer.writerow(["shadowed", logical, shadow.pack_name, shadow.archive, shadow.entry,
                                 shadow.priority, shadow.crc, shadow.size, shadow.compressed_size, ""])
                shadow_count += 1

    report = {
        "schema": 2,
        "origin": "PR #183 artifact + pinned exact-pack fixture",
        "exact_pack_sha256": EXPECTED_EXACT_PACK,
        "semantic_contract_sha256": replay["semantic_sha256"],
        "contract_roots": len(contract["roots"]),
        "contract_entries": len(contract["entries"]),
        "contract_winners_verified_entry_and_sha": contract_verified,
        "selected_external_resource_packs_low_to_high": selected,
        "external_zip_archives": archives,
        "external_asset_candidates": len(candidates),
        "external_logical_resources": len(grouped),
        "winner_requests": winner_count,
        "winner_uncompressed_bytes": winner_uncompressed,
        "winner_compressed_bytes": winner_compressed,
        "shadowed_candidates": shadow_count,
        "request_semantics": "one winning valid asset read per logical external resource; earlier external candidates retained only for precedence validation",
    }
    (out / "provenance.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
