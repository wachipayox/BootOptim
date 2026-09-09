#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

ML_COMPONENT = "cpw.mods:modlauncher:11.0.5"
SJH_COMPONENT = "cpw.mods:securejarhandler:3.0.8"
MIXIN_COMPONENT = "net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--resolution", required=True, type=Path)
    parser.add_argument("--modlauncher-build", required=True, type=Path)
    parser.add_argument("--mixin-build", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    resolution = json.loads(args.resolution.read_text(encoding="utf-8"))
    ml_build = json.loads(args.modlauncher_build.read_text(encoding="utf-8"))
    mixin_build = json.loads(args.mixin_build.read_text(encoding="utf-8"))

    records: dict[Path, list[dict]] = {}
    for config in resolution.get("configurations", []):
        for artifact in config.get("artifacts", []):
            path = Path(artifact["file"]).resolve()
            if path.is_file():
                records.setdefault(path, []).append({
                    "configuration": config.get("name"),
                    "component": artifact.get("component"),
                    "artifact": artifact.get("artifact"),
                })

    found = {"modlauncher": {}, "securejarhandler": {}, "mixin": {}}
    for path, provenance in records.items():
        try:
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if "cpw/mods/modlauncher/Launcher.class" in names:
                    found["modlauncher"][path] = provenance
                if "cpw/mods/cl/ModuleClassLoader.class" in names:
                    found["securejarhandler"][path] = provenance
                if "org/spongepowered/asm/mixin/transformer/MixinProcessor.class" in names:
                    found["mixin"][path] = provenance
        except zipfile.BadZipFile:
            pass

    for key in found:
        if len(found[key]) != 1:
            raise SystemExit(f"expected exactly one {key} core JAR, found {list(found[key])}")

    ml_path, ml_prov = next(iter(found["modlauncher"].items()))
    sjh_path, sjh_prov = next(iter(found["securejarhandler"].items()))
    mixin_path, mixin_prov = next(iter(found["mixin"].items()))

    if sha256(ml_path) != ml_build.get("jar_sha256"):
        raise SystemExit("resolved ModLauncher is not the just-built diagnostic fork")
    if sha256(mixin_path) != mixin_build.get("jar_sha256"):
        raise SystemExit("resolved Mixin is not the just-built diagnostic fork")
    if not any(item.get("component") == ML_COMPONENT for item in ml_prov):
        raise SystemExit(f"unexpected ModLauncher provenance: {ml_prov}")
    if not any(item.get("component") == SJH_COMPONENT for item in sjh_prov):
        raise SystemExit(f"unexpected SecureJarHandler provenance: {sjh_prov}")
    if not any(item.get("component") == MIXIN_COMPONENT for item in mixin_prov):
        raise SystemExit(f"unexpected Mixin provenance: {mixin_prov}")

    with zipfile.ZipFile(ml_path) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace").replace("\r", "")
        if "BootOptim-Fork-Probe: agent94-post-accept-v2\n" not in manifest:
            raise SystemExit("ModLauncher probe manifest marker missing")
    with zipfile.ZipFile(mixin_path) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace").replace("\r", "")
        if "BootOptim-Mixin-Probe: agent94-mixin-lifecycle-v1\n" not in manifest:
            raise SystemExit("Mixin probe manifest marker missing")
        if "BootOptim-Mixin-Upstream-Commit: 023e39334850e839c283be413257bf459f40a5d6\n" not in manifest:
            raise SystemExit("Mixin upstream provenance marker missing")

    result = {
        "schema": 1,
        "modlauncher": {"path": str(ml_path), "sha256": sha256(ml_path), "provenance": ml_prov},
        "securejarhandler": {"path": str(sjh_path), "sha256": sha256(sjh_path), "provenance": sjh_prov},
        "mixin": {"path": str(mixin_path), "sha256": sha256(mixin_path), "provenance": mixin_prov},
        "counts": {key: len(value) for key, value in found.items()},
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_AGENT94_LIFECYCLE_PREFLIGHT " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
