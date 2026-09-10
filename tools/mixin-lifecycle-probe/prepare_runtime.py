#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

UPSTREAM_REPO = "https://github.com/FabricMC/Mixin.git"
UPSTREAM_COMMIT = "023e39334850e839c283be413257bf459f40a5d6"
VERSION = "0.15.2+mixin.0.8.7"
GAV_PATH = Path("net/fabricmc/sponge-mixin") / VERSION
PROBE = "agent96-mixin-prepareconfigs-suffix-v2"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_attributes(raw: bytes) -> dict[str, str]:
    text = raw.decode("utf-8", errors="replace").replace("\r\n", "\n").replace("\r", "\n")
    unfolded: list[str] = []
    for line in text.splitlines():
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    attrs: dict[str, str] = {}
    for line in unfolded:
        if ": " in line:
            key, value = line.split(": ", 1)
            attrs[key] = value
    return attrs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    output = args.output.resolve()
    report = args.report.resolve()
    jdk21 = os.environ.get("BOOTOPTIM_JDK21", "").strip()
    if not jdk21:
        raise SystemExit("BOOTOPTIM_JDK21 is required to build the pinned Fabric Mixin fork")

    with tempfile.TemporaryDirectory(prefix="bootoptim-mixin-") as tmp:
        checkout = Path(tmp) / "Mixin"
        subprocess.run(["git", "clone", "--filter=blob:none", "--no-checkout", UPSTREAM_REPO, str(checkout)], check=True)
        subprocess.run(["git", "-c", "advice.detachedHead=false", "checkout", "--detach", UPSTREAM_COMMIT], cwd=checkout, check=True)
        subprocess.run([
            sys.executable, os.fspath(root / "tools/mixin-lifecycle-probe/apply_probe.py"), "--checkout", os.fspath(checkout)
        ], cwd=root, check=True)

        env = os.environ.copy()
        env["JAVA_HOME"] = jdk21
        env["PATH"] = str(Path(jdk21) / "bin") + os.pathsep + env.get("PATH", "")
        env["CI"] = "true"
        gradle = checkout / ("gradlew.bat" if os.name == "nt" else "gradlew")
        if os.name != "nt":
            gradle.chmod(gradle.stat().st_mode | 0o111)
        subprocess.run([
            os.fspath(gradle), "stagingProguardJar", "generatePomFileForDeveloperPublication",
            "--no-daemon", "--console=plain"
        ], cwd=checkout, env=env, check=True)

        jar = checkout / "build/libs" / f"sponge-mixin-{VERSION}.jar"
        pom = checkout / "build/publications/developer/pom-default.xml"
        if not jar.is_file() or not pom.is_file():
            raise SystemExit(f"Fabric Mixin build outputs missing: jar={jar.is_file()} pom={pom.is_file()}")
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            if "org/spongepowered/asm/mixin/transformer/MixinProcessor.class" not in names:
                raise SystemExit("diagnostic Mixin JAR missing MixinProcessor.class")
            attrs = manifest_attributes(archive.read("META-INF/MANIFEST.MF"))
            if attrs.get("BootOptim-Mixin-Probe") != PROBE:
                raise SystemExit("diagnostic Mixin JAR missing BootOptim-Mixin-Probe manifest marker")
            if attrs.get("BootOptim-Mixin-Upstream-Commit") != UPSTREAM_COMMIT:
                raise SystemExit("diagnostic Mixin JAR missing upstream commit marker")

        target = output / GAV_PATH
        if output.exists():
            shutil.rmtree(output)
        target.mkdir(parents=True, exist_ok=True)
        staged_jar = target / f"sponge-mixin-{VERSION}.jar"
        staged_pom = target / f"sponge-mixin-{VERSION}.pom"
        shutil.copy2(jar, staged_jar)
        shutil.copy2(pom, staged_pom)

    payload = {
        "schema": 1,
        "gav": f"net.fabricmc:sponge-mixin:{VERSION}",
        "probe": PROBE,
        "upstream_repo": UPSTREAM_REPO,
        "upstream_commit": UPSTREAM_COMMIT,
        "jar": str(staged_jar),
        "jar_sha256": sha256(staged_jar),
        "repository": str(output),
    }
    report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
