#!/usr/bin/env python3
"""Offline, hash-pinned migration for Glowing Trim Armors v5.0 custom-name CIT keys.

This tool never downloads or publishes the third-party pack. It accepts only the
exact pack fingerprint audited by BootOptim, writes a separate transformed ZIP,
creates an exact source backup, and emits a per-rule semantic manifest.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import platform
import re
import shutil
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path

SCHEMA = 2
TOOL_ID = "bootoptim-glowing-trim-custom-name-v1"
PACK_NAME = "Glowing Trim Armors v5.0.zip"
SOURCE_SHA256 = "06250ff06cb373fb203ae251975ac396f3cca7f3df80c2023c5607ca70874fb0"
SOURCE_SIZE = 13_429_945
SOURCE_ENTRIES = 21_916
SOURCE_PROPERTIES = 7_922
SOURCE_LEGACY_RULES = 7_920
SOURCE_LEGACY_FILES = 7_920
SOURCE_MODERN_RULES = 0

LEGACY_KEY = "nbt.display.Name"
MODERN_KEY = "components.minecraft:custom_name"
MODERN_RAW = b"components.minecraft\\:custom_name"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def decode_property_escapes(raw: bytes) -> str:
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
        mapping = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
        if esc in mapping:
            out.append(mapping[esc])
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


def find_unescaped_equals(body: bytes, start: int) -> tuple[int, int] | None:
    """Return (key_end, value_start) for the exact audited `key=value` form.

    The pinned 7,920 target rules were previously proven to use `=`. CITResewn
    also has a colon fallback when an entire logical line contains no equals,
    but broadening the transformer to unaudited separator forms would weaken the
    hash-pinned contract. Escaped equals are skipped.
    """
    escaped = False
    for i in range(start, len(body)):
        b = body[i]
        if escaped:
            escaped = False
            continue
        if b == 0x5C:
            escaped = True
            continue
        if b == 0x3D:
            key_end = i
            while key_end > start and body[key_end - 1] in (0x20, 0x09, 0x0C):
                key_end -= 1
            value_start = i + 1
            while value_start < len(body) and body[value_start] in (0x20, 0x09, 0x0C):
                value_start += 1
            return key_end, value_start
    return None


@dataclass(frozen=True)
class Rule:
    line_index: int
    key_start: int
    key_end: int
    value_start: int
    semantic_key: str
    raw_key: bytes
    rhs: bytes


def inspect_properties(data: bytes) -> list[Rule]:
    rules: list[Rule] = []
    for line_index, line in enumerate(data.splitlines(keepends=True)):
        body = line.rstrip(b"\r\n")
        leading = len(body) - len(body.lstrip(b" \t\f"))
        if leading >= len(body) or body[leading:leading + 1] in (b"#", b"!"):
            continue
        sep = find_unescaped_equals(body, leading)
        if sep is None:
            continue
        key_end, value_start = sep
        if key_end == leading:
            continue
        raw_key = body[leading:key_end]
        rules.append(Rule(
            line_index=line_index,
            key_start=leading,
            key_end=key_end,
            value_start=value_start,
            semantic_key=decode_property_escapes(raw_key),
            raw_key=raw_key,
            rhs=body[value_start:],
        ))
    return rules


def classify_rhs(rhs: bytes) -> str:
    text = rhs.decode("utf-8", errors="strict")
    for prefix in ("iregex:", "ipattern:", "regex:", "pattern:"):
        if text.startswith(prefix):
            return prefix[:-1]
    return "direct"


def audit_payload(data: bytes) -> dict[str, list[Rule]]:
    rules = inspect_properties(data)
    return {
        "legacy": [r for r in rules if r.semantic_key == LEGACY_KEY],
        "legacy_suffix": [r for r in rules if r.semantic_key.startswith(LEGACY_KEY + ".")],
        "modern": [r for r in rules if r.semantic_key == MODERN_KEY],
        "modern_suffix": [r for r in rules if r.semantic_key.startswith(MODERN_KEY + ".")],
    }


def migrate_payload(data: bytes, entry_name: str) -> tuple[bytes, list[dict]]:
    lines = data.splitlines(keepends=True)
    audit = audit_payload(data)
    if audit["legacy_suffix"]:
        raise ValueError(f"unsupported legacy suffix form in pinned pack: {entry_name}")
    if audit["modern"] or audit["modern_suffix"]:
        raise ValueError(f"modern-key collision in pinned pack: {entry_name}")
    if not audit["legacy"]:
        return data, []
    if len(audit["legacy"]) != 1:
        raise ValueError(f"expected exactly one legacy custom-name rule in {entry_name}")

    rule = audit["legacy"][0]
    if not rule.rhs:
        raise ValueError(f"empty RHS in {entry_name}:{rule.line_index + 1}")
    line = lines[rule.line_index]
    lines[rule.line_index] = line[:rule.key_start] + MODERN_RAW + line[rule.key_end:]
    migrated = b"".join(lines)

    after = audit_payload(migrated)
    if len(after["legacy"]) != 0 or len(after["modern"]) != 1:
        raise ValueError(f"post-transform key cardinality mismatch in {entry_name}")
    new_rule = after["modern"][0]
    if new_rule.rhs != rule.rhs:
        raise ValueError(f"RHS drift in {entry_name}")

    record = {
        "entry": entry_name,
        "line": rule.line_index + 1,
        "old_semantic_key": LEGACY_KEY,
        "new_semantic_key": MODERN_KEY,
        "old_raw_key_sha256": sha256_bytes(rule.raw_key),
        "new_raw_key_sha256": sha256_bytes(new_rule.raw_key),
        "rhs_sha256": sha256_bytes(rule.rhs),
        "rhs_bytes": len(rule.rhs),
        "matcher": classify_rhs(rule.rhs),
        "rhs_has_unicode_escape": bool(re.search(br"\\u[0-9A-Fa-f]{4}", rule.rhs)),
        "rhs_has_section_sign": b"\xc2\xa7" in rule.rhs or b"\\u00a7" in rule.rhs.lower(),
        "rhs_has_backslash": b"\\" in rule.rhs,
    }
    return migrated, [record]


def inverse_payload(data: bytes, entry_name: str) -> bytes:
    lines = data.splitlines(keepends=True)
    audit = audit_payload(data)
    if audit["legacy"] or audit["legacy_suffix"] or audit["modern_suffix"]:
        raise ValueError(f"unexpected key state while reversing {entry_name}")
    if not audit["modern"]:
        return data
    if len(audit["modern"]) != 1:
        raise ValueError(f"expected exactly one modern rule while reversing {entry_name}")
    rule = audit["modern"][0]
    line = lines[rule.line_index]
    lines[rule.line_index] = line[:rule.key_start] + LEGACY_KEY.encode("ascii") + line[rule.key_end:]
    return b"".join(lines)


def clone_zipinfo(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    new = copy.copy(info)
    new.CRC = 0
    new.compress_size = 0
    new.file_size = 0
    new.header_offset = 0
    return new


def ordered_payload_digest(records: list[tuple[str, str]]) -> str:
    h = hashlib.sha256()
    for name, payload_hash in records:
        h.update(name.encode("utf-8"))
        h.update(b"\0")
        h.update(payload_hash.encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()


def verify_source_identity(source: Path) -> None:
    if source.name != PACK_NAME:
        raise SystemExit(f"wrong filename: expected {PACK_NAME!r}, got {source.name!r}")
    size = source.stat().st_size
    digest = sha256_file(source)
    if size != SOURCE_SIZE or digest != SOURCE_SHA256:
        raise SystemExit(
            "source fingerprint mismatch; refusing migration: "
            f"size={size} sha256={digest} expected_size={SOURCE_SIZE} expected_sha256={SOURCE_SHA256}"
        )


def audit_source(source: Path) -> dict:
    verify_source_identity(source)
    entries = properties = legacy_files = legacy = modern = legacy_suffix = modern_suffix = 0
    duplicates: list[str] = []
    names: set[str] = set()
    matcher_counts: dict[str, int] = {}
    rhs_unicode_escapes = rhs_section_sign = rhs_backslash = 0
    records: list[tuple[str, str]] = []
    with zipfile.ZipFile(source, "r") as zf:
        infos = zf.infolist()
        entries = len(infos)
        for info in infos:
            if info.filename in names:
                duplicates.append(info.filename)
            names.add(info.filename)
            payload = zf.read(info)
            records.append((info.filename, sha256_bytes(payload)))
            if info.is_dir() or not info.filename.lower().endswith(".properties"):
                continue
            properties += 1
            a = audit_payload(payload)
            file_legacy = len(a["legacy"])
            legacy += file_legacy
            legacy_files += int(file_legacy > 0)
            modern += len(a["modern"])
            legacy_suffix += len(a["legacy_suffix"])
            modern_suffix += len(a["modern_suffix"])
            for r in a["legacy"]:
                if not r.rhs:
                    raise SystemExit(f"empty RHS: {info.filename}:{r.line_index + 1}")
                kind = classify_rhs(r.rhs)
                matcher_counts[kind] = matcher_counts.get(kind, 0) + 1
                rhs_unicode_escapes += int(bool(re.search(br"\\u[0-9A-Fa-f]{4}", r.rhs)))
                rhs_section_sign += int(b"\xc2\xa7" in r.rhs or b"\\u00a7" in r.rhs.lower())
                rhs_backslash += int(b"\\" in r.rhs)

    expected = {
        "entries": (SOURCE_ENTRIES, entries),
        "properties": (SOURCE_PROPERTIES, properties),
        "legacy_rules": (SOURCE_LEGACY_RULES, legacy),
        "legacy_files": (SOURCE_LEGACY_FILES, legacy_files),
        "modern_rules": (SOURCE_MODERN_RULES, modern),
        "legacy_suffix_rules": (0, legacy_suffix),
        "modern_suffix_rules": (0, modern_suffix),
        "duplicate_entries": (0, len(duplicates)),
    }
    bad = {k: v for k, v in expected.items() if v[0] != v[1]}
    if bad:
        raise SystemExit(f"pinned-pack structural mismatch: {bad}")
    return {
        "sha256": SOURCE_SHA256,
        "size": SOURCE_SIZE,
        "entries": entries,
        "properties": properties,
        "legacy_rules": legacy,
        "legacy_files": legacy_files,
        "modern_rules": modern,
        "legacy_suffix_rules": legacy_suffix,
        "modern_suffix_rules": modern_suffix,
        "ordered_payload_sha256": ordered_payload_digest(records),
        "matcher_counts": dict(sorted(matcher_counts.items())),
        "rhs_unicode_escape_rules": rhs_unicode_escapes,
        "rhs_section_sign_rules": rhs_section_sign,
        "rhs_backslash_rules": rhs_backslash,
    }


def build_candidate(source: Path, output: Path, backup: Path, manifest: Path) -> dict:
    source_audit = audit_source(source)
    output = output.resolve()
    source = source.resolve()
    backup = backup.resolve()
    manifest = manifest.resolve()
    for target in (output, backup, manifest):
        target.parent.mkdir(parents=True, exist_ok=True)
    if output == source or backup == source:
        raise SystemExit("output/backup must be separate from source; original is never edited in place")

    if backup.exists():
        if sha256_file(backup) != SOURCE_SHA256:
            raise SystemExit(f"existing backup has wrong hash: {backup}")
    else:
        tmp_backup = backup.with_name(backup.name + ".tmp")
        shutil.copyfile(source, tmp_backup)
        if sha256_file(tmp_backup) != SOURCE_SHA256:
            tmp_backup.unlink(missing_ok=True)
            raise SystemExit("backup verification failed")
        os.replace(tmp_backup, backup)

    tmp = output.with_name(output.name + ".tmp")
    tmp.unlink(missing_ok=True)
    rules: list[dict] = []
    originals: list[tuple[str, str]] = []
    reconstructed: list[tuple[str, str]] = []
    metadata_mismatches: list[str] = []
    unchanged_mismatches: list[str] = []

    with zipfile.ZipFile(source, "r") as zin, zipfile.ZipFile(tmp, "w", allowZip64=True) as zout:
        zout.comment = zin.comment
        for info in zin.infolist():
            original = zin.read(info)
            originals.append((info.filename, sha256_bytes(original)))
            transformed = original
            entry_rules: list[dict] = []
            if not info.is_dir() and info.filename.lower().endswith(".properties"):
                transformed, entry_rules = migrate_payload(original, info.filename)
            rules.extend(entry_rules)
            if not entry_rules and transformed != original:
                unchanged_mismatches.append(info.filename)
            if entry_rules:
                if inverse_payload(transformed, info.filename) != original:
                    raise SystemExit(f"inverse reconstruction failed: {info.filename}")
                reconstructed_payload = inverse_payload(transformed, info.filename)
            else:
                reconstructed_payload = transformed
            reconstructed.append((info.filename, sha256_bytes(reconstructed_payload)))
            zout.writestr(clone_zipinfo(info), transformed, compress_type=info.compress_type)

    if len(rules) != SOURCE_LEGACY_RULES:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"changed rule count mismatch: {len(rules)}")
    if unchanged_mismatches:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"unexpected unchanged payload drift: {unchanged_mismatches[:5]}")

    with zipfile.ZipFile(source, "r") as before, zipfile.ZipFile(tmp, "r") as after:
        if before.comment != after.comment:
            metadata_mismatches.append("archive_comment")
        if len(before.infolist()) != len(after.infolist()):
            metadata_mismatches.append("entry_count")
        fields = ("filename", "date_time", "compress_type", "comment", "extra", "internal_attr", "external_attr", "create_system", "create_version", "extract_version")
        for a, b in zip(before.infolist(), after.infolist()):
            for field in fields:
                if getattr(a, field) != getattr(b, field):
                    metadata_mismatches.append(f"{a.filename}:{field}")
    if metadata_mismatches:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"ZIP metadata drift: {metadata_mismatches[:10]}")

    original_digest = ordered_payload_digest(originals)
    reconstructed_digest = ordered_payload_digest(reconstructed)
    if original_digest != reconstructed_digest or original_digest != source_audit["ordered_payload_sha256"]:
        tmp.unlink(missing_ok=True)
        raise SystemExit("aggregate inverse-reconstruction digest mismatch")

    os.replace(tmp, output)
    output_hash = sha256_file(output)

    after_legacy = after_modern = 0
    with zipfile.ZipFile(output, "r") as zf:
        for info in zf.infolist():
            if info.is_dir() or not info.filename.lower().endswith(".properties"):
                continue
            a = audit_payload(zf.read(info))
            after_legacy += len(a["legacy"]) + len(a["legacy_suffix"])
            after_modern += len(a["modern"]) + len(a["modern_suffix"])
    if (after_legacy, after_modern) != (0, SOURCE_LEGACY_RULES):
        output.unlink(missing_ok=True)
        raise SystemExit(f"candidate postcondition mismatch: legacy={after_legacy} modern={after_modern}")

    matcher_counts: dict[str, int] = {}
    for r in rules:
        matcher_counts[r["matcher"]] = matcher_counts.get(r["matcher"], 0) + 1
    rule_digest = sha256_bytes(json.dumps(rules, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    report = {
        "schema": SCHEMA,
        "tool": TOOL_ID,
        "source": source_audit,
        "backup": {"path": str(backup), "sha256": sha256_file(backup)},
        "output": {
            "path": str(output),
            "sha256": output_hash,
            "size": output.stat().st_size,
            "legacy_rules": after_legacy,
            "modern_rules": after_modern,
            "changed_rules": len(rules),
            "rule_manifest_sha256": rule_digest,
            "matcher_counts": dict(sorted(matcher_counts.items())),
            "inverse_reconstructed_ordered_payload_sha256": reconstructed_digest,
        },
        "invariants": {
            "entry_order_preserved": True,
            "checked_zip_metadata_preserved": True,
            "archive_comment_preserved": True,
            "unchanged_payloads_byte_identical": True,
            "rhs_bytes_preserved_per_rule": True,
            "inverse_reconstruction_matches_source": True,
            "source_never_modified_in_place": True,
        },
        "environment": {
            "python": platform.python_version(),
            "zlib": zlib.ZLIB_VERSION,
            "platform": platform.platform(),
        },
        "rules": rules,
        "redistribution_note": "Generated derivative is not licensed for BootOptim redistribution; source project is All Rights Reserved. Keep output local unless author permission exists.",
    }
    tmp_manifest = manifest.with_name(manifest.name + ".tmp")
    tmp_manifest.write_text(json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp_manifest, manifest)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    audit = sub.add_parser("audit", help="verify exact source fingerprint and print structural/matcher audit")
    audit.add_argument("--input", required=True, type=Path)
    transform = sub.add_parser("transform", help="write separate modernized ZIP + exact backup + per-rule manifest")
    transform.add_argument("--input", required=True, type=Path)
    transform.add_argument("--output", required=True, type=Path)
    transform.add_argument("--backup", required=True, type=Path)
    transform.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    if args.command == "audit":
        print(json.dumps(audit_source(args.input.resolve()), indent=2, sort_keys=True))
    else:
        report = build_candidate(args.input, args.output, args.backup, args.manifest)
        print(json.dumps({"status": "ok", "output": report["output"], "backup": report["backup"]}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
