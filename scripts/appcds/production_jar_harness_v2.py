#!/usr/bin/env python3
"""Production-launch parity shim for the hosted AppCDS JAR-only harness.

The first hosted run exposed that the initial materializer did not mirror the
NeoForge 1.21.1 RunProductionClient inheritance algorithm exactly.  Keep the
original diagnostic harness intact, but replace its launcher materialization
and argument expansion with the version-pinned production semantics used by
NeoForge's own 1.21.1 end-to-end production-client task:

* classpath libraries are traversed child -> parent;
* override identity excludes the version but retains classifier + extension;
* the original client jar is copied to versions/<child-id>/<child-id>.jar and
  appended last, so NeoForge's ignoreList=${version_name}.jar applies;
* version_name is the installed child profile id, not a doubly-prefixed id.

No CDS policy is relaxed here.  Archive generation/consumption, strong identity,
resource/reload gates, application shared-class hit proof and javaagent refusal
remain in production_jar_harness.py.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path
from typing import Any

import launcher_materialize as lm
import production_jar_harness as base


def coordinate_override_key(name: str) -> tuple[str, str, str, str]:
    """Match NeoForge 1.21.1 MavenIdentifier override identity minus version."""
    parts = name.split(":")
    if len(parts) < 3:
        raise ValueError(f"bad Maven coordinate: {name}")
    group, artifact, version = parts[:3]
    classifier = parts[3] if len(parts) > 3 else ""
    extension = "jar"
    if "@" in classifier:
        classifier, extension = classifier.split("@", 1)
    elif "@" in version:
        _version, extension = version.split("@", 1)
    return group, artifact, classifier, extension


def production_library_order(parent: dict[str, Any], child: dict[str, Any], features: dict[str, bool]) -> list[dict[str, Any]]:
    """Reproduce NeoForge RunProductionClient: child first, then unique parent libs."""
    result: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str, str]] = set()
    for manifest in (child, parent):
        for lib in manifest.get("libraries", []):
            if not lm.allowed(lib, features):
                continue
            name = lib.get("name")
            if not isinstance(name, str):
                raise RuntimeError(f"launcher library without Maven name: {lib!r}")
            key = coordinate_override_key(name)
            if key in seen:
                continue
            seen.add(key)
            result.append(lib)
    return result


def fixed_materialize_launcher(workspace: Path, minecraft: str, neoforge: str, java: Path) -> dict[str, Any]:
    # Let the existing materializer perform the pinned downloads, official
    # --install-client processors, profile-payload equality check, assets and natives.
    state = lm.materialize_launcher(workspace, minecraft, neoforge, java)

    features = {"is_demo_user": False, "has_custom_resolution": False, "has_quick_plays_support": False}
    ordered_libs = production_library_order(state["vanilla"], state["neo"], features)

    classpath: list[Path] = []
    seen_paths: set[Path] = set()
    for lib in ordered_libs:
        path = lm.ensure_library(lib, state["libraries"])
        lm.ensure_natives(lib, state["libraries"], state["natives"])
        if path is not None:
            resolved = path.resolve()
            if resolved not in seen_paths:
                classpath.append(resolved)
                seen_paths.add(resolved)

    child_id = state["neo"].get("id")
    if not isinstance(child_id, str) or child_id != f"neoforge-{neoforge}":
        raise RuntimeError(f"unexpected installed NeoForge profile id: {child_id!r}")

    # NeoForge 1.21.1's own RunProductionClient copies the original client jar
    # under the child version id and appends that path last.  The child profile's
    # ignoreList references ${version_name}.jar, so using versions/1.21.1/1.21.1.jar
    # changes BootstrapLauncher module ownership and is not production-equivalent.
    original_game_jar = state["launcher"] / "versions" / minecraft / f"{minecraft}.jar"
    if not original_game_jar.is_file():
        raise RuntimeError(f"original Minecraft client jar missing: {original_game_jar}")
    production_game_jar = state["launcher"] / "versions" / child_id / f"{child_id}.jar"
    production_game_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original_game_jar, production_game_jar)
    classpath.append(production_game_jar.resolve())

    state["classpath"] = classpath
    state["production_game_jar"] = production_game_jar.resolve()

    evidence = Path(workspace).resolve() / "evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    lm.atomic_json(evidence / "launcher-parity.json", {
        "schema": 1,
        "model": "NeoForge-1.21.1-RunProductionClient",
        "child_profile_id": child_id,
        "version_name": child_id,
        "library_traversal": "child-then-parent",
        "override_identity": "group:artifact:classifier:extension-without-version",
        "production_game_jar": production_game_jar.resolve().relative_to(state["launcher"]).as_posix(),
        "production_game_jar_sha256": lm.sha(production_game_jar),
        "ordered_library_coordinates": [lib["name"] for lib in ordered_libs],
        "ordered_classpath": [path.relative_to(state["launcher"]).as_posix() for path in classpath],
    })
    return state


def fixed_build_launch(repo: Path, state: dict[str, Any], pack: Path, java: Path, base_jvm: list[str]) -> tuple[list[str], list[str], list[str]]:
    cp = lm.os.pathsep.join(str(path) for path in state["classpath"])
    child_id = state["neo"].get("id")
    if not isinstance(child_id, str):
        raise RuntimeError("NeoForge child profile has no id")
    substitutions = {
        "natives_directory": str(state["natives"]),
        "launcher_name": "bootoptim-hosted-production-jar-harness",
        "launcher_version": "1",
        "classpath": cp,
        "classpath_separator": lm.os.pathsep,
        "library_directory": str(state["libraries"]),
        "auth_player_name": "BootOptimCI",
        "version_name": child_id,
        "game_directory": str(pack.resolve()),
        "assets_root": str(state["assets"]),
        "assets_index_name": state["vanilla"]["assetIndex"]["id"],
        "auth_uuid": base.uuid.UUID(int=0).hex,
        "auth_access_token": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
        "resolution_width": "1280",
        "resolution_height": "720",
    }
    features = {"is_demo_user": False, "has_custom_resolution": False, "has_quick_plays_support": False}
    vanilla_jvm = lm.effective_arguments(state["vanilla"].get("arguments", {}).get("jvm", []), features, substitutions)
    neo_jvm = lm.effective_arguments(state["neo"].get("arguments", {}).get("jvm", []), features, substitutions)
    jvm = base_jvm + vanilla_jvm + neo_jvm
    vanilla_game = lm.effective_arguments(state["vanilla"].get("arguments", {}).get("game", []), features, substitutions)
    neo_game = lm.effective_arguments(state["neo"].get("arguments", {}).get("game", []), features, substitutions)
    game = vanilla_game + neo_game
    main_class = state["neo"]["mainClass"]
    if base.classpath_has_directory(state["classpath"]):
        raise RuntimeError("non-empty directory present on production class path")
    base.reject_agents(jvm)

    ignore_args = [arg for arg in jvm if arg.startswith("-DignoreList=")]
    expected_suffix = child_id + ".jar"
    if len(ignore_args) != 1 or expected_suffix not in ignore_args[0].split("=", 1)[1].split(","):
        raise RuntimeError(f"production profile does not ignore its child game jar {expected_suffix}: {ignore_args}")
    if Path(state["classpath"][-1]).name != expected_suffix:
        raise RuntimeError(f"last classpath entry is not production child game jar: {state['classpath'][-1]}")
    return jvm, [main_class], game


def extended_self_test() -> None:
    original = base.self_test
    original()
    assert coordinate_override_key("g:a:1") == ("g", "a", "", "jar")
    assert coordinate_override_key("g:a:2:client") == ("g", "a", "client", "jar")
    assert coordinate_override_key("g:a:3:client@zip") == ("g", "a", "client", "zip")
    parent = {"libraries": [
        {"name": "g:a:1"},
        {"name": "g:a:1:extra"},
        {"name": "g:c:1"},
    ]}
    child = {"libraries": [
        {"name": "g:a:2"},
        {"name": "g:a:2:extra"},
        {"name": "g:b:1"},
    ]}
    got = [entry["name"] for entry in production_library_order(parent, child, {})]
    assert got == ["g:a:2", "g:a:2:extra", "g:b:1", "g:c:1"], got
    print("APP_CDS_PRODUCTION_JAR_HARNESS v2 launcher-parity self-test ok")


# Patch only the two launcher-parity seams.  All archive policy/gates remain in
# the original harness and therefore cannot be accidentally weakened by this shim.
base.materialize_launcher = fixed_materialize_launcher
base.build_launch = fixed_build_launch
base.self_test = extended_self_test

if __name__ == "__main__":
    raise SystemExit(base.main())
