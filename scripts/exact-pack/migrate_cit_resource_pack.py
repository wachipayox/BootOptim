#!/usr/bin/env python3
import argparse
import copy
import hashlib
import json
import os
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path

PACK_NAME = "Glowing Trim Armors v5.0.zip"
EXPECTED_SHA256 = "06250ff06cb373fb203ae251975ac396f3cca7f3df80c2023c5607ca70874fb0"
EXPECTED_SIZE = 13_429_945
EXPECTED_ENTRIES = 21_916
EXPECTED_PROPERTIES = 7_922
EXPECTED_LEGACY_KEYS = 7_920
EXPECTED_LEGACY_FILES = 7_920

LEGACY_SEMANTIC_KEY = "nbt.display.Name"
MODERN_SEMANTIC_KEY = "components.minecraft:custom_name"
MODERN_RAW_KEY = b"components.minecraft\\:custom_name"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_property_escapes(raw: bytes) -> str:
    # Mirrors CITResewn PropertiesGroupAdapter for key text closely enough to
    # identify aliases/collisions without reserializing payloads.
    text = raw.decode("utf-8", errors="strict")
    out: list[str] = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        i += 1
        if i >= len(text):
            raise ValueError("dangling property escape in key")
        esc = text[i]
        if esc == "n":
            out.append("\n")
        elif esc == "r":
            out.append("\r")
        elif esc == "f":
            out.append("\f")
        elif esc == "t":
            out.append("\t")
        elif esc == "u":
            if i + 4 >= len(text):
                raise ValueError("short unicode escape in property key")
            code = text[i + 1:i + 5]
            if not re.fullmatch(r"[0-9A-Fa-f]{4}", code):
                raise ValueError(f"invalid unicode escape in property key: {code!r}")
            out.append(chr(int(code, 16)))
            i += 4
        else:
            out.append(esc)
        i += 1
    return "".join(out)


def find_unescaped_equals(line: bytes) -> int | None:
    escaped = False
    for index, byte in enumerate(line):
        if escaped:
            escaped = False
            continue
        if byte == 0x5C:
            escaped = True
            continue
        if byte == 0x3D:
            return index
    return None


@dataclass(frozen=True)
class PropertyOccurrence:
    line_index: int
    key_start: int
    key_end: int
    equals_index: int
    semantic_key: str
    value_bytes: bytes


def inspect_properties(data: bytes) -> list[PropertyOccurrence]:
    lines = data.splitlines(keepends=True)
    occurrences: list[PropertyOccurrence] = []
    for line_index, line in enumerate(lines):
        body = line.rstrip(b"\r\n")
        leading_len = len(body) - len(body.lstrip(b" \t\f"))
        stripped = body[leading_len:]
        if not stripped or stripped.startswith((b"#", b"!")):
            continue
        equals_rel = find_unescaped_equals(stripped)
        if equals_rel is None:
            continue
        equals_index = leading_len + equals_rel
        key_region = body[leading_len:equals_index]
        key_trimmed = key_region.rstrip(b" \t\f")
        key_end = leading_len + len(key_trimmed)
        if not key_trimmed:
            continue
        semantic_key = decode_property_escapes(key_trimmed)
        occurrences.append(PropertyOccurrence(
            line_index=line_index,
            key_start=leading_len,
            key_end=key_end,
            equals_index=equals_index,
            semantic_key=semantic_key,
            value_bytes=body[equals_index + 1:],
        ))
    return occurrences


def audit_payload(data: bytes) -> dict:
    occ = inspect_properties(data)
    legacy = [o for o in occ if o.semantic_key == LEGACY_SEMANTIC_KEY]
    legacy_suffix = [o for o in occ if o.semantic_key.startswith(LEGACY_SEMANTIC_KEY + ".")]
    modern = [o for o in occ if o.semantic_key == MODERN_SEMANTIC_KEY]
    modern_suffix = [o for o in occ if o.semantic_key.startswith(MODERN_SEMANTIC_KEY + ".")]
    return {
        "legacy": legacy,
        "legacy_suffix": legacy_suffix,
        "modern": modern,
        "modern_suffix": modern_suffix,
    }


def migrate_payload(data: bytes) -> tuple[bytes, int, list[str]]:
    lines = data.splitlines(keepends=True)
    audit = audit_payload(data)
    targets = audit["legacy"] + audit["legacy_suffix"]
    if not targets:
        return data, 0, []

    modern_semantics = {o.semantic_key for o in audit["modern"] + audit["modern_suffix"]}
    collisions = []
    for target in targets:
        suffix = target.semantic_key[len(LEGACY_SEMANTIC_KEY):]
        modern_key = MODERN_SEMANTIC_KEY + suffix
        if modern_key in modern_semantics:
            collisions.append(modern_key)
        if not target.value_bytes.strip(b" \t\f"):
            raise ValueError(f"empty RHS at physical line {target.line_index + 1}")
    if collisions:
        raise ValueError(f"modern-key collision(s): {sorted(set(collisions))}")

    by_line = {target.line_index: target for target in targets}
    changed_keys: list[str] = []
    for line_index, target in by_line.items():
        line = lines[line_index]
        suffix = target.semantic_key[len(LEGACY_SEMANTIC_KEY):]
        replacement = MODERN_RAW_KEY + suffix.encode("utf-8")
        lines[line_index] = line[:target.key_start] + replacement + line[target.key_end:]
        changed_keys.append(target.semantic_key)
    return b"".join(lines), len(targets), changed_keys


def reverse_payload(data: bytes) -> bytes:
    lines = data.splitlines(keepends=True)
    audit = audit_payload(data)
    targets = audit["modern"] + audit["modern_suffix"]
    by_line = {target.line_index: target for target in targets}
    for line_index, target in by_line.items():
        line = lines[line_index]
        suffix = target.semantic_key[len(MODERN_SEMANTIC_KEY):]
        replacement = LEGACY_SEMANTIC_KEY.encode("ascii") + suffix.encode("utf-8")
        lines[line_index] = line[:target.key_start] + replacement + line[target.key_end:]
    return b"".join(lines)


def clone_zipinfo(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    new = copy.copy(info)
    new.CRC = 0
    new.compress_size = 0
    new.file_size = 0
    new.header_offset = 0
    return new


def ordered_payload_digest(records: list[tuple[str, str]]) -> str:
    digest = hashlib.sha256()
    for name, payload_sha in records:
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(payload_sha.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def build_variant(source: Path, output: Path, mode: str) -> dict:
    if source.name != PACK_NAME:
        raise SystemExit(f"Unexpected target filename. expected={PACK_NAME!r} actual={source.name!r}")
    actual_size = source.stat().st_size
    actual_sha = sha256_file(source)
    if actual_size != EXPECTED_SIZE or actual_sha != EXPECTED_SHA256:
        raise SystemExit(
            "Target resource-pack identity mismatch. "
            f"expected_size={EXPECTED_SIZE} actual_size={actual_size} "
            f"expected_sha256={EXPECTED_SHA256} actual_sha256={actual_sha}"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    tmp = output.with_name(output.name + ".tmp")
    tmp.unlink(missing_ok=True)

    entries = 0
    properties = 0
    legacy_files = 0
    legacy_keys = 0
    modern_before = 0
    legacy_suffix_before = 0
    modern_suffix_before = 0
    changed_entries = 0
    unchanged_entries = 0
    reverse_failures = []
    payload_identity_failures = []
    metadata_mismatches = []
    duplicate_names = []
    names_seen = set()
    original_records: list[tuple[str, str]] = []
    reconstructed_records: list[tuple[str, str]] = []

    with zipfile.ZipFile(source, "r") as zin, zipfile.ZipFile(tmp, "w", allowZip64=True) as zout:
        zout.comment = zin.comment
        infos = zin.infolist()
        entries = len(infos)
        for info in infos:
            if info.filename in names_seen:
                duplicate_names.append(info.filename)
            names_seen.add(info.filename)
            original = zin.read(info)
            original_sha = sha256_bytes(original)
            original_records.append((info.filename, original_sha))

            transformed = original
            changed = 0
            if info.filename.lower().endswith(".properties") and not info.is_dir():
                properties += 1
                audit = audit_payload(original)
                file_legacy = len(audit["legacy"])
                file_legacy_suffix = len(audit["legacy_suffix"])
                file_modern = len(audit["modern"])
                file_modern_suffix = len(audit["modern_suffix"])
                if file_legacy or file_legacy_suffix:
                    legacy_files += 1
                legacy_keys += file_legacy
                legacy_suffix_before += file_legacy_suffix
                modern_before += file_modern
                modern_suffix_before += file_modern_suffix
                if mode == "candidate" and (file_legacy or file_legacy_suffix):
                    transformed, changed, _ = migrate_payload(original)
                    if reverse_payload(transformed) != original:
                        reverse_failures.append(info.filename)
            if changed:
                changed_entries += 1
            else:
                unchanged_entries += 1
                if transformed != original:
                    payload_identity_failures.append(info.filename)

            reconstructed = reverse_payload(transformed) if changed else transformed
            reconstructed_records.append((info.filename, sha256_bytes(reconstructed)))
            zout.writestr(clone_zipinfo(info), transformed, compress_type=info.compress_type, compresslevel=None)

    if duplicate_names:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"Duplicate ZIP entry names are not allowed: {duplicate_names[:10]}")
    expected_counts = {
        "entries": (EXPECTED_ENTRIES, entries),
        "properties": (EXPECTED_PROPERTIES, properties),
        "legacy_keys": (EXPECTED_LEGACY_KEYS, legacy_keys),
        "legacy_files": (EXPECTED_LEGACY_FILES, legacy_files),
        "legacy_suffix_keys": (0, legacy_suffix_before),
        "modern_keys_before": (0, modern_before),
        "modern_suffix_keys_before": (0, modern_suffix_before),
    }
    bad_counts = {key: pair for key, pair in expected_counts.items() if pair[0] != pair[1]}
    if bad_counts:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"Pinned resource-pack count mismatch: {bad_counts}")
    if reverse_failures or payload_identity_failures:
        tmp.unlink(missing_ok=True)
        raise SystemExit(
            f"Payload integrity failure reverse={reverse_failures[:5]} unchanged={payload_identity_failures[:5]}"
        )

    with zipfile.ZipFile(source, "r") as zin, zipfile.ZipFile(tmp, "r") as zout:
        if len(zin.infolist()) != len(zout.infolist()):
            metadata_mismatches.append("entry_count")
        for before, after in zip(zin.infolist(), zout.infolist()):
            fields = (
                "filename", "date_time", "compress_type", "comment", "extra",
                "internal_attr", "external_attr", "create_system", "create_version",
                "extract_version",
            )
            for field in fields:
                if getattr(before, field) != getattr(after, field):
                    metadata_mismatches.append(f"{before.filename}:{field}")
        if zin.comment != zout.comment:
            metadata_mismatches.append("archive_comment")
    if metadata_mismatches:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"ZIP metadata preservation failure: {metadata_mismatches[:10]}")

    os.replace(tmp, output)
    output_sha = sha256_file(output)

    after_legacy = 0
    after_modern = 0
    after_legacy_files = 0
    after_modern_files = 0
    with zipfile.ZipFile(output, "r") as archive:
        for info in archive.infolist():
            if not info.filename.lower().endswith(".properties") or info.is_dir():
                continue
            audit = audit_payload(archive.read(info))
            legacy_count = len(audit["legacy"]) + len(audit["legacy_suffix"])
            modern_count = len(audit["modern"]) + len(audit["modern_suffix"])
            after_legacy += legacy_count
            after_modern += modern_count
            after_legacy_files += int(legacy_count > 0)
            after_modern_files += int(modern_count > 0)

    expected_after = (
        (EXPECTED_LEGACY_KEYS, 0) if mode == "control" else (0, EXPECTED_LEGACY_KEYS)
    )
    if (after_legacy, after_modern) != expected_after:
        output.unlink(missing_ok=True)
        raise SystemExit(
            f"Variant postcondition mismatch mode={mode} legacy={after_legacy} modern={after_modern} expected={expected_after}"
        )

    source_payload_digest = ordered_payload_digest(original_records)
    reconstructed_payload_digest = ordered_payload_digest(reconstructed_records)
    if source_payload_digest != reconstructed_payload_digest:
        output.unlink(missing_ok=True)
        raise SystemExit("Aggregate reconstructed payload digest does not match source")

    return {
        "schema": 1,
        "pack_name": PACK_NAME,
        "mode": mode,
        "source": {
            "sha256": actual_sha,
            "size": actual_size,
            "entries": entries,
            "properties": properties,
            "legacy_keys": legacy_keys,
            "legacy_files": legacy_files,
            "legacy_suffix_keys": legacy_suffix_before,
            "modern_keys": modern_before,
            "modern_suffix_keys": modern_suffix_before,
            "ordered_payload_sha256": source_payload_digest,
        },
        "output": {
            "sha256": output_sha,
            "size": output.stat().st_size,
            "changed_entries": changed_entries,
            "unchanged_entries": unchanged_entries,
            "legacy_keys": after_legacy,
            "legacy_files": after_legacy_files,
            "modern_keys": after_modern,
            "modern_files": after_modern_files,
            "metadata_mismatches": 0,
            "unchanged_payload_mismatches": 0,
            "reverse_payload_mismatches": 0,
            "reconstructed_ordered_payload_sha256": reconstructed_payload_digest,
        },
        "invariants": {
            "rhs_bytes_preserved": True,
            "entry_order_preserved": True,
            "entry_metadata_preserved": True,
            "unchanged_payloads_identical": True,
            "candidate_payloads_reverse_exact": True,
            "archive_comment_preserved": True,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build an ephemeral control/candidate copy of the pinned CIT resource pack.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--mode", required=True, choices=("control", "candidate"))
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    report = build_variant(args.input.resolve(), args.output.resolve(), args.mode)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
