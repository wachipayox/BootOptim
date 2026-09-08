#!/usr/bin/env python3
"""Build a FilePackResources ZIP I/O replay from the exact-pack and PR #183 contract.

This helper only reconstructs the user-selected external resource-pack precedence for
ordinary model JSON resources. It deliberately does not parse model semantics. The
PR #183 manifest/replay remains the semantic oracle for its bounded roots.
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

MODEL_RE = re.compile(r"^assets/([^/]+)/models/(.+)\.json$")
EXPECTED_DIGEST = "502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050"
EXPECTED_EXACT_PACK = "7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639"


@dataclass(frozen=True)
class Candidate:
    logical_id: str
    pack_name: str
    archive: Path
    entry: str
    priority: int
    crc: int
    size: int
    compressed_size: int


def model_id(entry: str) -> str | None:
    match = MODEL_RE.match(entry.replace("\\", "/"))
    return f"{match.group(1)}:{match.group(2)}" if match else None


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
            # Directory packs are real resources but not FilePackResources ZIP targets.
            continue
        try:
            with zipfile.ZipFile(path) as zf:
                infos = [i for i in zf.infolist() if not i.is_dir() and model_id(i.filename)]
                counts: dict[str, int] = {}
                for info in infos:
                    counts[info.filename] = counts.get(info.filename, 0) + 1
                duplicate_names = {name for name, count in counts.items() if count > 1}
                accepted = 0
                for info in infos:
                    # Match #183: refuse duplicate model paths inside one archive rather than
                    # inventing duplicate-name precedence.
                    if info.filename in duplicate_names:
                        continue
                    logical = model_id(info.filename)
                    assert logical is not None
                    candidates.append(Candidate(logical, name, path.resolve(), info.filename,
                                                priority, info.CRC, info.file_size, info.compress_size))
                    accepted += 1
                archives.append({"pack": name, "path": str(path.resolve()),
                                 "central_entries": len(zf.infolist()),
                                 "accepted_model_entries": accepted,
                                 "duplicate_model_names_refused": len(duplicate_names),
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
        grouped.setdefault(c.logical_id, []).append(c)
    for values in grouped.values():
        values.sort(key=lambda c: (c.priority, c.pack_name, c.entry))

    contract_by_id = {e["id"]: e for e in contract["entries"]}
    # First prove the bounded #183 winners still resolve to the exact same archive entry
    # and bytes under the external-pack precedence reconstructed here.
    contract_verified = 0
    for logical_id, expected in contract_by_id.items():
        winner = grouped.get(logical_id, [None])[-1]
        if winner is None:
            raise RuntimeError(f"contract winner disappeared: {logical_id}")
        exp_winner = expected["winner"]
        if exp_winner["source_id"] != f"external-pack:{winner.pack_name}" or exp_winner["entry"] != winner.entry:
            raise RuntimeError(f"winner provenance mismatch for {logical_id}")
        if sha_for(winner.archive, winner.entry) != expected["sha256"]:
            raise RuntimeError(f"winner bytes mismatch for {logical_id}")
        contract_verified += 1

    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    tsv = out / "requests.tsv"
    winner_count = 0
    shadow_count = 0
    with tsv.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, delimiter="\t", lineterminator="\n")
        writer.writerow(["role", "logical_id", "pack", "archive", "entry", "priority",
                         "crc", "size", "compressed_size", "expected_sha256"])
        for logical_id in sorted(grouped):
            values = grouped[logical_id]
            winner = values[-1]
            expected_sha = contract_by_id.get(logical_id, {}).get("sha256", "")
            writer.writerow(["winner", logical_id, winner.pack_name, winner.archive, winner.entry,
                             winner.priority, winner.crc, winner.size, winner.compressed_size, expected_sha])
            winner_count += 1
            for shadow in values[:-1]:
                writer.writerow(["shadowed", logical_id, shadow.pack_name, shadow.archive, shadow.entry,
                                 shadow.priority, shadow.crc, shadow.size, shadow.compressed_size, ""])
                shadow_count += 1

    report = {
        "schema": 1,
        "origin": "PR #183 artifact + pinned exact-pack fixture",
        "exact_pack_sha256": EXPECTED_EXACT_PACK,
        "semantic_contract_sha256": replay["semantic_sha256"],
        "contract_roots": len(contract["roots"]),
        "contract_entries": len(contract["entries"]),
        "contract_winners_verified_entry_and_sha": contract_verified,
        "selected_external_resource_packs_low_to_high": selected,
        "external_zip_archives": archives,
        "external_model_candidates": len(candidates),
        "external_logical_models": len(grouped),
        "winner_requests": winner_count,
        "shadowed_candidates": shadow_count,
        "request_semantics": "one winner read per logical external model; shadowed rows validate precedence but are excluded from timed winner reads",
    }
    (out / "provenance.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
