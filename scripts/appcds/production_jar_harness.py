#!/usr/bin/env python3
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
import uuid
import zipfile
from pathlib import Path
from typing import Any

from launcher_materialize import (
    artifact_path, allowed, atomic_json, effective_arguments, library_key, materialize_launcher, os_name, sha,
)

SCHEMA = 1
MAIN_MENU_MARKER = "BOOTOPTIM_STARTUP phase=main_menu"
SHARED_PREFIXES = ("net.neoforged.", "cpw.mods.", "org.spongepowered.asm.", "net.minecraft.", "dev.wachipayox.")
AGENT_MARKERS = ("-javaagent", "-agentlib", "-agentpath", "-XX:+AllowArchivingWithJavaAgent")


def file_identity(path: Path, root: Path | None = None) -> dict[str, Any]:
    resolved = path.resolve()
    label = resolved.as_posix()
    if root:
        try:
            label = resolved.relative_to(root.resolve()).as_posix()
        except ValueError:
            pass
    return {"path": label, "size": resolved.stat().st_size, "sha256": sha(resolved)}


def java_identity(java: Path) -> dict[str, Any]:
    home = java.resolve().parent.parent
    release = home / "release"
    probe = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True)
    return {
        "java_executable": file_identity(java),
        "release": file_identity(release) if release.is_file() else None,
        "version_output": (probe.stderr or probe.stdout).strip().splitlines(),
    }


def validate_distributable(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"BootOptim distributable not found: {path}")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if "META-INF/jarjar/metadata.json" not in names:
            raise RuntimeError(f"not the packaged bootstrap/JarJar distributable: {path}")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        if "FMLModType: LIBRARY" not in manifest.replace("\r\n", "\n"):
            raise RuntimeError(f"bootstrap distributable lacks FMLModType=LIBRARY: {path}")


def reject_agents(args: list[str]) -> None:
    for arg in args:
        if any(arg.startswith(marker) for marker in AGENT_MARKERS):
            raise RuntimeError(f"archive generation under Java/JVMTI agent/escape hatch is forbidden: {arg}")
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        value = os.environ.get(key, "")
        if any(marker in value for marker in AGENT_MARKERS):
            raise RuntimeError(f"{key} injects an agent/archiving escape hatch; refusing archive generation")


def terminate_tree(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=10)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def run_game(command: list[str], cwd: Path, console: Path, timeout: int, env: dict[str, str]) -> None:
    console.parent.mkdir(parents=True, exist_ok=True)
    with console.open("w", encoding="utf-8", errors="replace") as output:
        process = subprocess.Popen(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True, env=env)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            terminate_tree(process)
            raise RuntimeError(f"production JAR-only launch timed out after {timeout}s")
    text = console.read_text(encoding="utf-8", errors="replace")
    if MAIN_MENU_MARKER not in text:
        tail = "\n".join(text.splitlines()[-200:])
        raise RuntimeError(f"launch exited {process.returncode} without main-menu endpoint\n{tail}")
    if process.returncode != 0:
        raise RuntimeError(f"launch reached endpoint but exited nonzero: {process.returncode}")


def classpath_has_directory(classpath: list[Path]) -> bool:
    return any(path.is_dir() for path in classpath)


def shared_application_hits(log: Path) -> tuple[int, list[str]]:
    hits: list[str] = []
    pattern = re.compile(r"\[class,load\]\s+([^ ]+)\s+source:\s+shared objects file")
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        match = pattern.search(line)
        if match and match.group(1).startswith(SHARED_PREFIXES):
            hits.append(match.group(1))
    return len(hits), hits[:50]


def copy_bootoptim(pack: Path, source: Path) -> Path:
    mods = pack / "mods"
    existing = [p for p in mods.glob("*.jar") if "bootoptim" in p.name.lower() or "boot_optim" in p.name.lower()]
    if existing:
        raise RuntimeError(f"pack already contains BootOptim: {[p.name for p in existing]}")
    target = mods / source.name
    shutil.copy2(source, target)
    return target


def check_resource_contract(repo: Path, reference: Path, pack: Path, latest: Path, output: Path) -> dict[str, Any]:
    command = [sys.executable, str(repo / "tools/laptop-bench/check_resource_selection.py"),
               "--reference", str(reference), "--options", str(pack / "options.txt"), "--log", str(latest)]
    result = subprocess.run(command, capture_output=True, text=True, cwd=repo)
    output.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"resource-selection contract failed; see {output}")
    data = json.loads(result.stdout)
    if data.get("reload_count") != 1:
        raise RuntimeError(f"expected exactly one resource reload, observed {data.get('reload_count')}")
    return data


def build_launch(repo: Path, state: dict[str, Any], pack: Path, java: Path, base_jvm: list[str]) -> tuple[list[str], list[str], list[str]]:
    cp = os.pathsep.join(str(path) for path in state["classpath"])
    substitutions = {
        "natives_directory": str(state["natives"]),
        "launcher_name": "bootoptim-hosted-production-jar-harness",
        "launcher_version": "1",
        "classpath": cp,
        "classpath_separator": os.pathsep,
        "library_directory": str(state["libraries"]),
        "auth_player_name": "BootOptimCI",
        "version_name": f"neoforge-{state['neo'].get('id', '')}",
        "game_directory": str(pack.resolve()),
        "assets_root": str(state["assets"]),
        "assets_index_name": state["vanilla"]["assetIndex"]["id"],
        "auth_uuid": uuid.UUID(int=0).hex,
        "auth_access_token": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
        "resolution_width": "1280",
        "resolution_height": "720",
    }
    features = {"is_demo_user": False, "has_custom_resolution": False, "has_quick_plays_support": False}
    vanilla_jvm = effective_arguments(state["vanilla"].get("arguments", {}).get("jvm", []), features, substitutions)
    neo_jvm = effective_arguments(state["neo"].get("arguments", {}).get("jvm", []), features, substitutions)
    jvm = base_jvm + vanilla_jvm + neo_jvm
    vanilla_game = effective_arguments(state["vanilla"].get("arguments", {}).get("game", []), features, substitutions)
    neo_game = effective_arguments(state["neo"].get("arguments", {}).get("game", []), features, substitutions)
    game = vanilla_game + neo_game
    main_class = state["neo"]["mainClass"]
    if classpath_has_directory(state["classpath"]):
        raise RuntimeError("non-empty directory present on production class path")
    reject_agents(jvm)
    return jvm, [main_class], game


def base_environment() -> dict[str, str]:
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    return env


def cds_gate(expected: dict[str, Any], current: dict[str, Any], archive: Path, log: Path) -> tuple[list[str], str]:
    """Return CDS flags only for an exact strong identity match; mismatch is stock/fail-open."""
    if expected != current:
        return [], "identity-mismatch"
    if not archive.is_file() or archive.stat().st_size <= 0:
        return [], "archive-missing"
    return [
        "-Xshare:auto",
        "-XX:+VerifySharedSpaces",
        f"-XX:SharedArchiveFile={archive}",
        f"-Xlog:cds=info,class+load=info:file={log}:time,level,tags",
    ], "identity-match"


def command_with(java: Path, jvm: list[str], main: list[str], game: list[str], cds: list[str]) -> list[str]:
    return [str(java)] + jvm + cds + main + game


def run_harness(args: argparse.Namespace) -> None:
    repo = Path(args.repo).resolve()
    workspace = Path(args.workspace).resolve()
    pack = Path(args.pack).resolve()
    java = Path(args.java).resolve()
    bootoptim = Path(args.bootoptim_jar).resolve()
    reference = Path(args.options_reference).resolve()
    output = workspace / "evidence"
    output.mkdir(parents=True, exist_ok=True)
    validate_distributable(bootoptim)
    injected = copy_bootoptim(pack, bootoptim)

    state = materialize_launcher(workspace, args.minecraft_version, args.neoforge_version, java)
    base_jvm = [line.strip() for line in Path(args.jvm_args_file).read_text(encoding="utf-8").splitlines() if line.strip()]
    base_jvm += ["-Dboot_optim.profileStartup=true", "-Dboot_optim.benchmark.exitOnTitle=true",
                 f"-Dboot_optim.version={args.bootoptim_version}"]
    jvm, main, game = build_launch(repo, state, pack, java, base_jvm)
    env = base_environment()

    mod_jars = sorted(pack.joinpath("mods").glob("*.jar"), key=lambda p: p.name.lower())
    identity = {
        "schema": SCHEMA,
        "kind": "hosted-linux-production-jar-surrogate",
        "production_equivalence": {
            "jar_only_minecraft_classpath": True,
            "neoforge_distribution_profile": True,
            "packaged_bootoptim_bootstrap": True,
            "prism_newlaunch_outer_process": False,
            "windows_equivalent": False,
        },
        "fixture_sha256": args.fixture_sha256,
        "platform": {"system": platform.system(), "machine": platform.machine(), "release": platform.release()},
        "java": java_identity(java),
        "minecraft": args.minecraft_version,
        "neoforge": args.neoforge_version,
        "launcher_inputs": [file_identity(state["installer"]), file_identity(state["vanilla_json"]), file_identity(state["neoforge_json"])],
        "ordered_classpath": [file_identity(path, state["launcher"]) for path in state["classpath"]],
        "mods": [file_identity(path, pack) for path in mod_jars],
        "bootoptim": file_identity(injected, pack),
        "jvm_args": jvm,
        "main_class": main[0],
        "game_args": game,
        "options_reference_sha256": sha(reference),
        "agents_forbidden": True,
    }
    atomic_json(output / "identity-prearchive.json", identity)
    (output / "ordered-classpath.txt").write_text("\n".join(str(p) for p in state["classpath"]) + "\n", encoding="utf-8")
    if any(Path(p).is_dir() for p in state["classpath"]):
        raise RuntimeError("JAR-only gate failed")

    archive = output / "bootoptim-hosted-linux.jsa"
    archive.unlink(missing_ok=True)
    generation_cds = ["-Xshare:auto", f"-XX:ArchiveClassesAtExit={archive}",
                      f"-Xlog:cds=info,class+load=info:file={output / 'cds-generate.log'}:time,level,tags"]
    generation = command_with(java, jvm, main, game, generation_cds)
    (output / "generation-command.json").write_text(json.dumps(generation, indent=2) + "\n", encoding="utf-8")
    run_game(generation, pack, output / "generation-console.log", args.timeout, env)
    if not archive.is_file() or archive.stat().st_size == 0:
        raise RuntimeError("ArchiveClassesAtExit did not produce a non-empty archive")
    generation_latest = pack / "logs" / "latest.log"
    shutil.copy2(generation_latest, output / "generation-latest.log")
    check_resource_contract(repo, reference, pack, output / "generation-latest.log", output / "generation-resource-selection.json")

    current_identity = {
        "ordered_classpath": [file_identity(path, state["launcher"]) for path in state["classpath"]],
        "mods": [file_identity(path, pack) for path in sorted(pack.joinpath("mods").glob("*.jar"), key=lambda p: p.name.lower())],
        "java": java_identity(java),
        "jvm_args": jvm,
    }
    expected_identity = {key: identity[key] for key in current_identity}

    stale_identity = json.loads(json.dumps(expected_identity))
    stale_identity["mods"][0]["sha256"] = "0" * 64
    fallback_flags, fallback_reason = cds_gate(stale_identity, current_identity, archive, output / "unused.log")
    if fallback_flags or fallback_reason != "identity-mismatch":
        raise RuntimeError("fail-open identity self-test did not disable CDS")
    atomic_json(output / "fallback-identity-test.json", {"active": False, "reason": fallback_reason, "flags": fallback_flags})

    candidate_cds, gate_reason = cds_gate(expected_identity, current_identity, archive, output / "cds-consume.log")
    atomic_json(output / "gate.json", {"active": bool(candidate_cds), "reason": gate_reason})
    if not candidate_cds:
        raise RuntimeError("fresh-process archive gate did not activate on exact identity")

    latest = pack / "logs" / "latest.log"
    startup = pack / "logs" / "bootoptim-startup.log"
    latest.unlink(missing_ok=True)
    startup.unlink(missing_ok=True)
    consumption = command_with(java, jvm, main, game, candidate_cds)
    (output / "consumption-command.json").write_text(json.dumps(consumption, indent=2) + "\n", encoding="utf-8")
    run_game(consumption, pack, output / "consumption-console.log", args.timeout, env)
    if not latest.is_file():
        raise RuntimeError("consumption reached endpoint but logs/latest.log is missing")
    shutil.copy2(latest, output / "consumption-latest.log")
    contract = check_resource_contract(repo, reference, pack, output / "consumption-latest.log", output / "consumption-resource-selection.json")
    latest_text = latest.read_text(encoding="utf-8", errors="replace")
    for marker in ("InvalidInjectionException", "Mixin apply for mod boot_optim failed", "Mixin prepare for mod boot_optim failed"):
        if marker in latest_text:
            raise RuntimeError(f"BootOptim/Mixin failure in consumption log: {marker}")

    count, examples = shared_application_hits(output / "cds-consume.log")
    if count <= 0:
        raise RuntimeError("archive was mapped but no NeoForge/Minecraft/BootOptim application shared-class hit was observed")

    final = dict(identity)
    final["archive"] = file_identity(archive, output)
    final["archive_application_shared_hits"] = count
    final["archive_application_shared_hit_examples"] = examples
    final["resource_contract"] = contract
    atomic_json(output / "manifest.json", final)
    print(f"APP_CDS_PRODUCTION_JAR_HARNESS success archive_hits={count} archive_sha256={final['archive']['sha256']}")


def self_test() -> None:
    assert artifact_path("a.b:c:1.2") == "a/b/c/1.2/c-1.2.jar"
    assert library_key("a:b:1") == "a:b"
    assert allowed({}, {})
    assert not allowed({"rules": [{"action": "allow", "os": {"name": "windows"}}]}, {}) if os_name() != "windows" else True
    try:
        reject_agents(["-javaagent:x.jar"])
    except RuntimeError:
        pass
    else:
        raise AssertionError("agent rejection failed")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        jar = root / "a.jar"
        jar.write_bytes(b"x")
        assert not classpath_has_directory([jar])
        assert classpath_has_directory([root])
        archive = root / "a.jsa"
        archive.write_bytes(b"archive")
        flags, reason = cds_gate({"x": 1}, {"x": 2}, archive, root / "log")
        assert flags == [] and reason == "identity-mismatch"
        flags, reason = cds_gate({"x": 1}, {"x": 1}, archive, root / "log")
        assert flags and "-Xshare:auto" in flags and "-Xshare:on" not in flags and reason == "identity-match"
    print("APP_CDS_PRODUCTION_JAR_HARNESS self-test ok")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("self-test")
    run = sub.add_parser("run")
    run.add_argument("--repo", default=".")
    run.add_argument("--workspace", required=True)
    run.add_argument("--pack", required=True)
    run.add_argument("--java", required=True)
    run.add_argument("--bootoptim-jar", required=True)
    run.add_argument("--bootoptim-version", required=True)
    run.add_argument("--options-reference", required=True)
    run.add_argument("--jvm-args-file", required=True)
    run.add_argument("--fixture-sha256", required=True)
    run.add_argument("--minecraft-version", default="1.21.1")
    run.add_argument("--neoforge-version", default="21.1.248")
    run.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
    else:
        run_harness(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
