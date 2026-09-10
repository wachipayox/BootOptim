#!/usr/bin/env python3
"""Build and stage the exact Agent 94 ModLauncher fork as a local Maven module."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

UPSTREAM = "https://github.com/McModLauncher/modlauncher.git"
COMMIT = "901c6ea849ae21ee7d464cd97113e77a6101a734"
VERSION = "11.0.5"
PROBE = "agent94-post-accept-v2"


def run(args: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    subprocess.run(args, cwd=cwd, env=env, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output.resolve()
    report = args.report.resolve()

    jdk21 = os.environ.get("BOOTOPTIM_JDK21", "").strip()
    if not jdk21:
        raise SystemExit("BOOTOPTIM_JDK21 is required to build pinned ModLauncher with Gradle 8.3")
    build_env = os.environ.copy()
    build_env["JAVA_HOME"] = jdk21
    build_env["PATH"] = str(Path(jdk21) / "bin") + os.pathsep + build_env.get("PATH", "")

    shutil.rmtree(output, ignore_errors=True)
    with tempfile.TemporaryDirectory(prefix="agent94-modlauncher-") as temp:
        source = Path(temp) / "modlauncher"
        run(["git", "clone", "--quiet", UPSTREAM, str(source)], root)
        run(["git", "checkout", "--quiet", "--detach", COMMIT], source)
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
        if actual != COMMIT:
            raise SystemExit(f"ModLauncher checkout drift: {actual}")

        run(["python3", str(root / "tools/modlauncher-fork-probe/apply_probe.py"), str(source)], root)
        run(["./gradlew", "jar", "generatePomFileForMavenJavaPublication", "--no-daemon", "--console=plain"], source, build_env)

        jar = source / "build/libs" / f"modlauncher-{VERSION}.jar"
        pom = source / "build/publications/mavenJava/pom-default.xml"
        if not jar.is_file() or not pom.is_file():
            raise SystemExit(f"Expected fork Maven outputs missing: jar={jar.is_file()} pom={pom.is_file()}")

        import zipfile
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace").replace("\r", "")
            if f"BootOptim-Fork-Probe: {PROBE}\n" not in manifest:
                raise SystemExit("Fork manifest probe marker missing")
            if f"BootOptim-Upstream-Commit: {COMMIT}\n" not in manifest:
                raise SystemExit("Fork upstream marker missing")
            if "cpw/mods/modlauncher/BootOptimForkTrace.class" not in archive.namelist():
                raise SystemExit("Fork trace class missing")

        target = output / "cpw/mods/modlauncher" / VERSION
        target.mkdir(parents=True, exist_ok=True)
        staged_jar = target / f"modlauncher-{VERSION}.jar"
        shutil.copy2(jar, staged_jar)
        shutil.copy2(pom, target / f"modlauncher-{VERSION}.pom")
        staged_sha256 = sha256(staged_jar)

    payload = {
        "schema": 2,
        "probe": PROBE,
        "upstream_commit": COMMIT,
        "gav": f"cpw.mods:modlauncher:{VERSION}",
        "repository": str(output),
        "jar": str(output / "cpw/mods/modlauncher" / VERSION / f"modlauncher-{VERSION}.jar"),
        "jar_sha256": staged_sha256,
    }
    report.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, sort_keys=True))


if __name__ == "__main__":
    main()
