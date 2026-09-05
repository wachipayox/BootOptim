#!/usr/bin/env python3
"""Record exact MoreCulling JAR identity from the pinned exact-pack fixture.

This is diagnostic metadata only. It never mutates the fixture and deliberately exits successfully
with a structured non-ok status when MoreCulling cannot be identified, so it cannot replace the
existing exact-pack resource/semantic gates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import tomllib
from typing import Any
from zipfile import BadZipFile, ZipFile


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_filename(name: str) -> str:
    return "".join(character for character in name.casefold() if character.isalnum())


def inspect_jar(path: Path) -> dict[str, Any]:
    result: dict[str, Any] = {
        "status": "ok",
        "filename": path.name,
        "sha256": sha256(path),
    }

    try:
        with ZipFile(path) as jar:
            names = set(jar.namelist())
            fabric_entry = "fabric.mod.json" if "fabric.mod.json" in names else None
            neoforge_entry = (
                "META-INF/neoforge.mods.toml"
                if "META-INF/neoforge.mods.toml" in names
                else "META-INF/mods.toml"
                if "META-INF/mods.toml" in names
                else None
            )

            result["fabric_metadata"] = fabric_entry
            result["neoforge_metadata"] = neoforge_entry
            if fabric_entry and neoforge_entry:
                result["platform"] = "hybrid"
            elif fabric_entry:
                result["platform"] = "fabric"
            elif neoforge_entry:
                result["platform"] = "neoforge"
            else:
                result["platform"] = "unknown"

            if fabric_entry:
                fabric = json.loads(jar.read(fabric_entry).decode("utf-8"))
                contact = fabric.get("contact") if isinstance(fabric.get("contact"), dict) else {}
                result["fabric"] = {
                    "id": fabric.get("id"),
                    "version": fabric.get("version"),
                    "name": fabric.get("name"),
                    "environment": fabric.get("environment"),
                    "source": contact.get("sources"),
                    "homepage": contact.get("homepage"),
                }

            if neoforge_entry:
                neo = tomllib.loads(jar.read(neoforge_entry).decode("utf-8"))
                mods = neo.get("mods") if isinstance(neo.get("mods"), list) else []
                first_mod = mods[0] if mods and isinstance(mods[0], dict) else {}
                result["neoforge"] = {
                    "modLoader": neo.get("modLoader"),
                    "loaderVersion": neo.get("loaderVersion"),
                    "modId": first_mod.get("modId"),
                    "version": first_mod.get("version"),
                    "displayName": first_mod.get("displayName"),
                    "displayURL": first_mod.get("displayURL"),
                }
    except (BadZipFile, OSError, UnicodeDecodeError, json.JSONDecodeError, tomllib.TOMLDecodeError) as exc:
        result["status"] = "error"
        result["error"] = f"{type(exc).__name__}: {exc}"

    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", type=Path, required=True, help="Extracted exact-pack root")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    mods = args.pack / "mods"
    matches = []
    if mods.is_dir():
        matches = sorted(
            path
            for path in mods.iterdir()
            if path.is_file()
            and path.suffix.casefold() == ".jar"
            and "moreculling" in normalize_filename(path.name)
        )

    if len(matches) == 1:
        result = inspect_jar(matches[0])
    else:
        result = {
            "status": "missing" if not matches else "ambiguous",
            "matches": [path.name for path in matches],
        }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_MORECULLING_FIXTURE " + json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
