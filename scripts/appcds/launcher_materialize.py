#!/usr/bin/env python3
"""Hosted Linux production-JAR launcher + CDS smoke harness for BootOptim.

This intentionally launches the distribution jars described by Mojang launchmeta and
NeoForge's embedded production version.json. It never places Gradle source-set/output
directories on the Java class path or module path.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
import zipfile
from pathlib import Path
from typing import Any, Iterable

MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"
MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
SCHEMA = 1
MAIN_MENU_MARKER = "BOOTOPTIM_STARTUP phase=main_menu"
SHARED_PREFIXES = (
    "net.neoforged.",
    "cpw.mods.",
    "org.spongepowered.asm.",
    "net.minecraft.",
    "dev.wachipayox.",
)
AGENT_MARKERS = ("-javaagent", "-agentlib", "-agentpath", "-XX:+AllowArchivingWithJavaAgent")


def sha(path: Path, algorithm: str = "sha256") -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass


def url_bytes(url: str, timeout: int = 120) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "BootOptim-AppCDS-hosted-harness/1"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def download(url: str, path: Path, *, expected: str | None = None, algorithm: str = "sha1") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and expected and sha(path, algorithm) == expected.lower():
        return path
    data = url_bytes(url)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_bytes(data)
    if expected:
        actual = sha(temporary, algorithm)
        if actual != expected.lower():
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"hash mismatch for {url}: expected={expected} actual={actual}")
    os.replace(temporary, path)
    return path


def parse_sidecar(text: str, algorithm: str) -> str:
    match = re.search(rf"\b[0-9a-fA-F]{{{hashlib.new(algorithm).digest_size * 2}}}\b", text)
    if not match:
        raise RuntimeError(f"could not parse {algorithm} sidecar")
    return match.group(0).lower()


def download_with_sidecar(url: str, path: Path, algorithm: str = "sha256") -> Path:
    algorithms = [algorithm] + (["sha1"] if algorithm != "sha1" else [])
    last_error: Exception | None = None
    for candidate in algorithms:
        try:
            expected = parse_sidecar(url_bytes(url + "." + candidate).decode("utf-8", "strict"), candidate)
            return download(url, path, expected=expected, algorithm=candidate)
        except Exception as exc:
            last_error = exc
    raise RuntimeError(f"no usable Maven checksum sidecar for {url}: {last_error}")


def artifact_path(name: str) -> str:
    parts = name.split(":")
    if len(parts) < 3:
        raise ValueError(f"bad Maven coordinate: {name}")
    group, artifact, version = parts[:3]
    classifier = parts[3] if len(parts) > 3 and parts[3] else None
    extension = "jar"
    if "@" in version:
        version, extension = version.split("@", 1)
    filename = f"{artifact}-{version}"
    if classifier:
        filename += f"-{classifier}"
    filename += f".{extension}"
    return f"{group.replace('.', '/')}/{artifact}/{version}/{filename}"


def os_name() -> str:
    system = platform.system().lower()
    return {"linux": "linux", "windows": "windows", "darwin": "osx"}.get(system, system)


def rule_matches(rule: dict[str, Any], features: dict[str, bool]) -> bool:
    target_os = rule.get("os", {})
    if "name" in target_os and target_os["name"] != os_name():
        return False
    if "arch" in target_os and re.fullmatch(target_os["arch"], platform.machine()) is None:
        return False
    if "version" in target_os and re.fullmatch(target_os["version"], platform.version()) is None:
        return False
    for key, expected in rule.get("features", {}).items():
        if bool(features.get(key, False)) != bool(expected):
            return False
    return True


def allowed(entry: dict[str, Any], features: dict[str, bool]) -> bool:
    rules = entry.get("rules")
    if not rules:
        return True
    result = False
    for rule in rules:
        if rule_matches(rule, features):
            result = rule.get("action") == "allow"
    return result


def expand_argument(value: str, substitutions: dict[str, str]) -> str:
    for key, replacement in substitutions.items():
        value = value.replace("${" + key + "}", replacement)
    unresolved = re.findall(r"\$\{[^}]+\}", value)
    if unresolved:
        raise RuntimeError(f"unresolved launcher placeholder(s) in {value!r}: {unresolved}")
    return value


def effective_arguments(items: Iterable[Any], features: dict[str, bool], substitutions: dict[str, str]) -> list[str]:
    result: list[str] = []
    for item in items:
        if isinstance(item, str):
            result.append(expand_argument(item, substitutions))
            continue
        if not isinstance(item, dict) or not allowed(item, features):
            continue
        value = item.get("value")
        values = value if isinstance(value, list) else [value]
        for part in values:
            if not isinstance(part, str):
                raise RuntimeError(f"invalid launcher argument: {item!r}")
            result.append(expand_argument(part, substitutions))
    return result


def library_key(name: str) -> str:
    parts = name.split(":")
    return ":".join(parts[:2]) if len(parts) >= 2 else name


def library_artifact(lib: dict[str, Any]) -> dict[str, Any] | None:
    return lib.get("downloads", {}).get("artifact")


def ensure_library(lib: dict[str, Any], libraries: Path) -> Path | None:
    # Installer-generated artifacts (notably neoforge:<version>:client) do not
    # exist on a remote Maven repository. Prefer an already-materialized local
    # coordinate before consulting download metadata.
    if "name" in lib:
        local = libraries / artifact_path(lib["name"])
        if local.is_file():
            return local
    artifact = library_artifact(lib)
    if artifact:
        relative = artifact.get("path") or artifact_path(lib["name"])
        url = artifact.get("url")
        if not url:
            base = lib.get("url") or MAVEN_CENTRAL
            url = base.rstrip("/") + "/" + relative
        return download(url, libraries / relative, expected=artifact.get("sha1"), algorithm="sha1")
    # NeoForge/older launcher metadata can omit a downloads block but provide a repository base.
    if "name" in lib:
        relative = artifact_path(lib["name"])
        base = lib.get("url") or MAVEN_CENTRAL
        return download(base.rstrip("/") + "/" + relative, libraries / relative)
    return None


def ensure_natives(lib: dict[str, Any], libraries: Path, natives: Path) -> None:
    mapping = lib.get("natives", {})
    classifier = mapping.get(os_name())
    if not classifier:
        return
    classifier = classifier.replace("${arch}", "64" if platform.architecture()[0] == "64bit" else "32")
    entry = lib.get("downloads", {}).get("classifiers", {}).get(classifier)
    if not entry:
        raise RuntimeError(f"native classifier missing from downloads: {lib.get('name')} {classifier}")
    path = download(entry["url"], libraries / entry["path"], expected=entry.get("sha1"), algorithm="sha1")
    excludes = tuple(lib.get("extract", {}).get("exclude", []))
    with zipfile.ZipFile(path) as archive:
        for member in archive.infolist():
            if member.is_dir() or member.filename.startswith(excludes) or member.filename.startswith("META-INF/"):
                continue
            target = (natives / member.filename).resolve()
            if natives.resolve() not in target.parents:
                raise RuntimeError(f"native archive path escape: {member.filename}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected object in {path}")
    return value


def materialize_launcher(workspace: Path, minecraft: str, neoforge: str, java: Path) -> dict[str, Any]:
    launcher = workspace / "launcher"
    libraries = launcher / "libraries"
    versions = launcher / "versions"
    assets = launcher / "assets"
    natives = launcher / "natives"
    shutil.rmtree(natives, ignore_errors=True)
    natives.mkdir(parents=True, exist_ok=True)

    manifest_path = launcher / "version_manifest_v2.json"
    manifest_data = url_bytes(MOJANG_MANIFEST)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_bytes(manifest_data)
    version_manifest = json.loads(manifest_data)
    version_entry = next((v for v in version_manifest["versions"] if v["id"] == minecraft), None)
    if version_entry is None:
        raise RuntimeError(f"Minecraft version not found in launchmeta: {minecraft}")
    vanilla_json_path = versions / minecraft / f"{minecraft}.json"
    download(version_entry["url"], vanilla_json_path, expected=version_entry["sha1"], algorithm="sha1")
    vanilla = read_json(vanilla_json_path)

    client = vanilla["downloads"]["client"]
    client_jar = versions / minecraft / f"{minecraft}.jar"
    download(client["url"], client_jar, expected=client["sha1"], algorithm="sha1")

    # Seed the ordinary launcher libraries before invoking the official installer.
    # Forge/NeoForge installers expect a valid launcher directory and run processors
    # that create the patched client artifact used by the production profile.
    features = {"is_demo_user": False, "has_custom_resolution": False, "has_quick_plays_support": False}
    for lib in [lib for lib in vanilla.get("libraries", []) if allowed(lib, features)]:
        ensure_library(lib, libraries)
    profiles = launcher / "launcher_profiles.json"
    if not profiles.exists():
        profiles.write_text('{"profiles":{},"settings":{},"version":3}\n', encoding="utf-8")

    installer_url = f"{NEOFORGE_MAVEN}/net/neoforged/neoforge/{neoforge}/neoforge-{neoforge}-installer.jar"
    installer = launcher / "installer" / f"neoforge-{neoforge}-installer.jar"
    download_with_sidecar(installer_url, installer, "sha256")
    with zipfile.ZipFile(installer) as archive:
        embedded_version_bytes = archive.read("version.json")
    installer_env = base_environment()
    install_log = launcher / "installer" / "install-client.log"
    with install_log.open("w", encoding="utf-8", errors="replace") as output:
        completed = subprocess.run(
            [str(java), "-jar", str(installer), "--install-client", str(launcher)],
            stdout=output, stderr=subprocess.STDOUT, env=installer_env, cwd=launcher, check=False, timeout=600)
    if completed.returncode != 0:
        raise RuntimeError(f"NeoForge production client install failed ({completed.returncode}); see {install_log}")
    neoforge_json_path = versions / f"neoforge-{neoforge}" / f"neoforge-{neoforge}.json"
    if not neoforge_json_path.is_file():
        raise RuntimeError(f"NeoForge installer did not materialize launcher profile: {neoforge_json_path}")
    if neoforge_json_path.read_bytes() != embedded_version_bytes:
        raise RuntimeError("installed NeoForge launcher profile differs from the signed installer payload")
    neo = read_json(neoforge_json_path)
    if neo.get("inheritsFrom") != minecraft:
        raise RuntimeError(f"NeoForge profile inherits {neo.get('inheritsFrom')!r}, expected {minecraft!r}")

    base_libs = [lib for lib in vanilla.get("libraries", []) if allowed(lib, features)]
    child_libs = [lib for lib in neo.get("libraries", []) if allowed(lib, features)]

    # Launcher inheritance semantics replace a parent library when the child supplies
    # the same group:artifact. Preserve parent slot order and append new child libs.
    child_by_key = {library_key(lib["name"]): lib for lib in child_libs if "name" in lib}
    merged: list[dict[str, Any]] = []
    consumed: set[str] = set()
    for lib in base_libs:
        key = library_key(lib.get("name", ""))
        replacement = child_by_key.get(key)
        if replacement is not None:
            merged.append(replacement)
            consumed.add(key)
        else:
            merged.append(lib)
    for lib in child_libs:
        key = library_key(lib.get("name", ""))
        if key not in consumed and not any(library_key(existing.get("name", "")) == key for existing in base_libs):
            merged.append(lib)
            consumed.add(key)

    classpath: list[Path] = []
    seen_paths: set[Path] = set()
    for lib in merged:
        path = ensure_library(lib, libraries)
        ensure_natives(lib, libraries, natives)
        if path and path not in seen_paths:
            classpath.append(path.resolve())
            seen_paths.add(path)
    classpath.append(client_jar.resolve())

    asset_index = vanilla["assetIndex"]
    index_path = assets / "indexes" / f"{asset_index['id']}.json"
    download(asset_index["url"], index_path, expected=asset_index["sha1"], algorithm="sha1")
    asset_data = read_json(index_path)
    for obj in asset_data.get("objects", {}).values():
        digest = obj["hash"]
        target = assets / "objects" / digest[:2] / digest
        download(f"https://resources.download.minecraft.net/{digest[:2]}/{digest}", target, expected=digest, algorithm="sha1")

    return {
        "launcher": launcher.resolve(),
        "libraries": libraries.resolve(),
        "assets": assets.resolve(),
        "natives": natives.resolve(),
        "vanilla": vanilla,
        "neo": neo,
        "classpath": classpath,
        "installer": installer.resolve(),
        "vanilla_json": vanilla_json_path.resolve(),
        "neoforge_json": neoforge_json_path.resolve(),
    }



def base_environment() -> dict[str, str]:
    env = os.environ.copy()
    # Generation/consumption identity must not inherit hidden JVM arguments. We already
    # reject an agent-bearing value; strip non-agent values so the recorded command is complete.
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    return env
