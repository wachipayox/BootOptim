#!/usr/bin/env python3
"""Strict fail-open identity manifest for a packaged CDS/AppCDS archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import tempfile
from pathlib import Path
from typing import Any

SCHEMA = 1
JAVA_RELEASE_KEYS = (
    "IMPLEMENTOR",
    "IMPLEMENTOR_VERSION",
    "JAVA_VERSION",
    "JAVA_RUNTIME_VERSION",
    "FULL_VERSION",
    "OS_ARCH",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_release(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="strict").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] == '"':
            value = value[1:-1]
        if key in JAVA_RELEASE_KEYS:
            result[key] = value
    return result


def java_identity(java_home: Path) -> dict[str, Any]:
    release = java_home / "release"
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not release.is_file():
        raise ValueError(f"missing Java release file: {release}")
    if not java.is_file():
        raise ValueError(f"missing Java executable: {java}")
    return {
        "release_sha256": sha256(release),
        "java_executable_sha256": sha256(java),
        "release": parse_release(release),
    }


def normalize_relative(root: Path, text: str) -> str:
    candidate = (root / text).resolve()
    try:
        return candidate.relative_to(root.resolve()).as_posix()
    except ValueError as exc:
        raise ValueError(f"input escapes root: {text}") from exc


def file_identity(root: Path, relative: str) -> dict[str, Any]:
    normalized = normalize_relative(root, relative)
    path = root / normalized
    if not path.is_file():
        raise ValueError(f"missing identity input: {normalized}")
    return {"path": normalized, "size": path.stat().st_size, "sha256": sha256(path)}


def platform_identity() -> dict[str, str]:
    return {"system": platform.system(), "machine": platform.machine()}


def manifest_for(args: argparse.Namespace) -> dict[str, Any]:
    root = Path(args.root).resolve()
    if not args.input:
        raise ValueError("at least one --input is required")
    # Order is intentional: callers can pass the exact Prism class-path order
    # followed by the remaining transform-affecting pack inputs.
    inputs = [file_identity(root, item) for item in args.input]
    archive_rel = normalize_relative(root, args.archive)
    return {
        "schema": SCHEMA,
        "pack_fingerprint": args.pack_fingerprint,
        "platform": platform_identity(),
        "java": java_identity(Path(args.java_home).resolve()),
        "inputs": inputs,
        "archive": file_identity(root, archive_rel),
    }


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent, text=True)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def current_for_manifest(manifest: dict[str, Any], root: Path, java_home: Path, pack_fingerprint: str) -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "pack_fingerprint": pack_fingerprint,
        "platform": platform_identity(),
        "java": java_identity(java_home),
        "inputs": [file_identity(root, entry["path"]) for entry in manifest["inputs"]],
        "archive": file_identity(root, manifest["archive"]["path"]),
    }


def mismatch_paths(expected: Any, actual: Any, prefix: str = "") -> list[str]:
    if type(expected) is not type(actual):
        return [prefix or "root"]
    if isinstance(expected, dict):
        result: list[str] = []
        for key in sorted(set(expected) | set(actual)):
            child = f"{prefix}.{key}" if prefix else str(key)
            if key not in expected or key not in actual:
                result.append(child)
            else:
                result.extend(mismatch_paths(expected[key], actual[key], child))
        return result
    if isinstance(expected, list):
        if len(expected) != len(actual):
            return [prefix or "root"]
        result: list[str] = []
        for index, (left, right) in enumerate(zip(expected, actual)):
            result.extend(mismatch_paths(left, right, f"{prefix}[{index}]"))
        return result
    return [] if expected == actual else [prefix or "root"]


def command_manifest(args: argparse.Namespace) -> int:
    manifest = manifest_for(args)
    atomic_write(Path(args.output), json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"APP_CDS_IDENTITY manifest={args.output} inputs={len(manifest['inputs'])}")
    return 0


def command_gate(args: argparse.Namespace) -> int:
    args_file = Path(args.args_file)
    # Fail open first so a stale previously-enabled argfile cannot survive a
    # mismatch, malformed manifest, interruption or verifier failure.
    atomic_write(args_file, "")
    try:
        manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
        if manifest.get("schema") != SCHEMA:
            raise ValueError(f"unsupported manifest schema: {manifest.get('schema')!r}")
        root = Path(args.root).resolve()
        current = current_for_manifest(
            manifest,
            root,
            Path(args.java_home).resolve(),
            args.pack_fingerprint,
        )
        mismatches = mismatch_paths(manifest, current)
        if mismatches:
            print("APP_CDS_GATE inactive mismatch=" + ",".join(mismatches))
            return 0
        archive = (root / manifest["archive"]["path"]).resolve()
        atomic_write(
            args_file,
            "\n".join((
                "-Xshare:auto",
                "-XX:+VerifySharedSpaces",
                f"-XX:SharedArchiveFile={archive}",
                "",
            )),
        )
        print(f"APP_CDS_GATE active archive={archive}")
    except Exception as exc:  # launcher gate is deliberately fail-open
        print(f"APP_CDS_GATE inactive error={type(exc).__name__}:{exc}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("manifest", help="record the exact archive identity")
    create.add_argument("--root", required=True)
    create.add_argument("--java-home", required=True)
    create.add_argument("--pack-fingerprint", required=True)
    create.add_argument("--archive", required=True, help="archive path relative to --root")
    create.add_argument(
        "--input",
        action="append",
        default=[],
        help="ordered launch/JAR input relative to --root; repeat in identity order",
    )
    create.add_argument("--output", required=True)
    create.set_defaults(func=command_manifest)

    gate = sub.add_parser("gate", help="atomically enable CDS only on an exact identity match")
    gate.add_argument("--root", required=True)
    gate.add_argument("--java-home", required=True)
    gate.add_argument("--pack-fingerprint", required=True)
    gate.add_argument("--manifest", required=True)
    gate.add_argument("--args-file", required=True)
    gate.set_defaults(func=command_gate)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
