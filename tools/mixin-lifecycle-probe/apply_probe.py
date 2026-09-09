#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

UPSTREAM_COMMIT = "023e39334850e839c283be413257bf459f40a5d6"
PROCESSOR_BLOB = "2cfa27c33d838c8c3e6e1e7114fb89a6cf568c6b"
BUILD_BLOB = "141b8076ec5f83c61987f7f2eb443a1d5c57300d"
PROCESSOR = Path("src/main/java/org/spongepowered/asm/mixin/transformer/MixinProcessor.java")
BUILD = Path("build.gradle")
PROBE = "agent94-mixin-lifecycle-v1"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact source match, found {count}")
    return text.replace(old, new, 1)


def git_blob(root: Path, path: Path) -> str:
    return subprocess.check_output(["git", "hash-object", str(path)], cwd=root, text=True).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkout", required=True, type=Path)
    args = parser.parse_args()
    root = args.checkout.resolve()

    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if head != UPSTREAM_COMMIT:
        raise SystemExit(f"unexpected Fabric Mixin HEAD {head}; expected {UPSTREAM_COMMIT}")
    if git_blob(root, PROCESSOR) != PROCESSOR_BLOB:
        raise SystemExit(f"MixinProcessor.java blob drift; expected {PROCESSOR_BLOB}")
    if git_blob(root, BUILD) != BUILD_BLOB:
        raise SystemExit(f"build.gradle blob drift; expected {BUILD_BLOB}")

    path = root / PROCESSOR
    text = path.read_text(encoding="utf-8")

    text = replace_once(text, "class MixinProcessor {\n", """class MixinProcessor {\n\n    private static final boolean BOOTOPTIM_LIFECYCLE_TRACE = Boolean.getBoolean(\"boot_optim.mixinLifecycleTrace\");\n\n    private static long bootOptimNow() {\n        return BOOTOPTIM_LIFECYCLE_TRACE ? System.nanoTime() : 0L;\n    }\n\n    private static void bootOptimTrace(String event, long startNanos, String detail) {\n        if (!BOOTOPTIM_LIFECYCLE_TRACE) {\n            return;\n        }\n        try {\n            long now = System.nanoTime();\n            String safeDetail = detail == null ? \"none\" : detail.replace(' ', '_').replace('\\t', '_').replace('\\n', '_').replace('\\r', '_');\n            System.err.printf(\"BOOTOPTIM_MIXIN_LIFECYCLE probe=%s event=%s mono_ns=%d elapsed_ns=%d thread=%s detail=%s%n\",\n                    \"agent94-mixin-lifecycle-v1\", event, now, startNanos == 0L ? 0L : now - startNanos,\n                    Thread.currentThread().getName(), safeDetail);\n        } catch (Throwable ignored) {\n            // Diagnostic must never change Mixin failure semantics.\n        }\n    }\n""", "trace helpers")

    old_check = """    private void checkSelect(MixinEnvironment environment) {\n        if (this.currentEnvironment != environment) {\n            this.select(environment);\n            return;\n        }\n        \n        int unvisitedCount = Mixins.getUnvisitedCount();\n        if (unvisitedCount > 0 && this.transformedCount == 0) {\n            this.select(environment);\n        }\n    }\n"""
    new_check = """    private void checkSelect(MixinEnvironment environment) {\n        long bootOptimStart = bootOptimNow();\n        bootOptimTrace(\"check_select_enter\", 0L, String.valueOf(environment.getPhase()));\n        if (this.currentEnvironment != environment) {\n            bootOptimTrace(\"check_select_trigger_environment_change\", 0L, String.valueOf(environment.getPhase()));\n            this.select(environment);\n            bootOptimTrace(\"check_select_exit\", bootOptimStart, \"environment_change\");\n            return;\n        }\n        \n        int unvisitedCount = Mixins.getUnvisitedCount();\n        if (unvisitedCount > 0 && this.transformedCount == 0) {\n            bootOptimTrace(\"check_select_trigger_unvisited\", 0L, Integer.toString(unvisitedCount));\n            this.select(environment);\n        }\n        bootOptimTrace(\"check_select_exit\", bootOptimStart, \"steady\");\n    }\n"""
    text = replace_once(text, old_check, new_check, "checkSelect")

    text = replace_once(text,
        """    private void select(MixinEnvironment environment) {\n        this.verboseLoggingLevel = (environment.getOption(Option.DEBUG_VERBOSE)) ? Level.INFO : Level.DEBUG;\n""",
        """    private void select(MixinEnvironment environment) {\n        long bootOptimSelectStart = bootOptimNow();\n        bootOptimTrace(\"select_enter\", 0L, String.valueOf(environment.getPhase()));\n        this.verboseLoggingLevel = (environment.getOption(Option.DEBUG_VERBOSE)) ? Level.INFO : Level.DEBUG;\n""", "select entry")

    text = replace_once(text,
        """        this.selectConfigs(environment);\n        this.extensions.select(environment);\n        int totalMixins = this.prepareConfigs(environment, this.extensions);\n""",
        """        long bootOptimPhaseStart = bootOptimNow();\n        this.selectConfigs(environment);\n        bootOptimTrace(\"select_configs_exit\", bootOptimPhaseStart, Integer.toString(this.pendingConfigs.size()));\n        bootOptimPhaseStart = bootOptimNow();\n        this.extensions.select(environment);\n        bootOptimTrace(\"extensions_select_exit\", bootOptimPhaseStart, String.valueOf(environment.getPhase()));\n        bootOptimPhaseStart = bootOptimNow();\n        int totalMixins = this.prepareConfigs(environment, this.extensions);\n        bootOptimTrace(\"prepare_configs_call_exit\", bootOptimPhaseStart, Integer.toString(totalMixins));\n""", "select phases")

    text = replace_once(text,
        """        this.profiler.mark(environment.getPhase().toString() + ":apply");\n        Profiler.setActive(environment.getOption(Option.DEBUG_PROFILER));\n    }\n""",
        """        this.profiler.mark(environment.getPhase().toString() + ":apply");\n        Profiler.setActive(environment.getOption(Option.DEBUG_PROFILER));\n        bootOptimTrace(\"select_exit\", bootOptimSelectStart, Integer.toString(totalMixins));\n    }\n""", "select exit")

    text = replace_once(text,
        """    private int prepareConfigs(MixinEnvironment environment, Extensions extensions) {\n        int totalMixins = 0;\n""",
        """    private int prepareConfigs(MixinEnvironment environment, Extensions extensions) {\n        long bootOptimPrepareConfigsStart = bootOptimNow();\n        bootOptimTrace(\"prepare_configs_enter\", 0L, Integer.toString(this.pendingConfigs.size()));\n        int totalMixins = 0;\n""", "prepareConfigs entry")

    text = replace_once(text,
        """        for (MixinConfig config : this.pendingConfigs) {\n            try {\n                MixinProcessor.logger.log(this.verboseLoggingLevel, \"Preparing {} ({})\", config, config.getDeclaredMixinCount());\n                config.prepare(extensions);\n                totalMixins += config.getMixinCount();\n""",
        """        for (MixinConfig config : this.pendingConfigs) {\n            try {\n                MixinProcessor.logger.log(this.verboseLoggingLevel, \"Preparing {} ({})\", config, config.getDeclaredMixinCount());\n                long bootOptimConfigStart = bootOptimNow();\n                bootOptimTrace(\"config_prepare_enter\", 0L, config.getName() + \"#declared=\" + config.getDeclaredMixinCount());\n                config.prepare(extensions);\n                bootOptimTrace(\"config_prepare_exit\", bootOptimConfigStart, config.getName() + \"#mixins=\" + config.getMixinCount());\n                totalMixins += config.getMixinCount();\n""", "config.prepare")

    text = replace_once(text,
        """            plugin.acceptTargets(config.getTargetsSet(), Collections.<String>unmodifiableSet(otherTargets));\n""",
        """            long bootOptimPluginStart = bootOptimNow();\n            bootOptimTrace(\"plugin_accept_targets_enter\", 0L, config.getName());\n            plugin.acceptTargets(config.getTargetsSet(), Collections.<String>unmodifiableSet(otherTargets));\n            bootOptimTrace(\"plugin_accept_targets_exit\", bootOptimPluginStart, config.getName());\n""", "plugin.acceptTargets")

    text = replace_once(text,
        """        for (MixinConfig config : this.pendingConfigs) {\n            try {\n                config.postInitialise(this.extensions);\n""",
        """        for (MixinConfig config : this.pendingConfigs) {\n            try {\n                long bootOptimPostStart = bootOptimNow();\n                bootOptimTrace(\"config_post_initialise_enter\", 0L, config.getName());\n                config.postInitialise(this.extensions);\n                bootOptimTrace(\"config_post_initialise_exit\", bootOptimPostStart, config.getName());\n""", "config.postInitialise")

    text = replace_once(text,
        """        this.pendingConfigs.clear();\n        \n        return totalMixins;\n""",
        """        this.pendingConfigs.clear();\n        bootOptimTrace(\"prepare_configs_exit\", bootOptimPrepareConfigsStart, Integer.toString(totalMixins));\n        \n        return totalMixins;\n""", "prepareConfigs exit")
    path.write_text(text, encoding="utf-8")

    build_path = root / BUILD
    build_text = build_path.read_text(encoding="utf-8")
    build_text = replace_once(build_text,
        """        \"Implementation-Vendor\": url,\n        // for hotswap agent\n""",
        """        \"Implementation-Vendor\": url,\n        \"BootOptim-Mixin-Probe\": \"agent94-mixin-lifecycle-v1\",\n        \"BootOptim-Mixin-Upstream-Commit\": \"023e39334850e839c283be413257bf459f40a5d6\",\n        // for hotswap agent\n""", "manifest provenance")
    build_path.write_text(build_text, encoding="utf-8")

    print(f"patched Fabric Mixin {UPSTREAM_COMMIT} with {PROBE}")


if __name__ == "__main__":
    main()
