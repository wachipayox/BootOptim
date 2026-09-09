#!/usr/bin/env python3
"""Apply the Agent 94 diagnostic patch to the exact ModLauncher source tree.

This does not inject or replace ModLauncher in a pack. It creates a uniquely marked,
version-pinned diagnostic fork artifact for later launch-layer replacement tests.
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

UPSTREAM_COMMIT = "901c6ea849ae21ee7d464cd97113e77a6101a734"
CLASS_TRANSFORMER_BLOB = "a0451dff688b78f075d0e79c3fba540361ba3304"
PROBE_ID = "agent94-post-accept-v1"

HELPER = r'''/* Agent 94 diagnostic-only fork probe. */
package cpw.mods.modlauncher;

import cpw.mods.modlauncher.api.ITransformer;
import java.util.Arrays;

final class BootOptimForkTrace {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.modlauncherForkTrace");
    private static final String TARGET = "net.minecraft.server.Bootstrap";

    private BootOptimForkTrace() {}

    static long begin(String className) {
        return ENABLED && TARGET.equals(className) ? System.nanoTime() : 0L;
    }

    static void end(String className, String stage, long startNanos) {
        if (startNanos == 0L || !TARGET.equals(className)) return;
        long elapsed = System.nanoTime() - startNanos;
        System.err.printf("BOOTOPTIM_ML_FORK probe=%s class=%s stage=%s elapsed_ns=%d thread=%s%n",
                "agent94-post-accept-v1", className, stage, elapsed, Thread.currentThread().getName());
    }

    static void transformer(String className, ITransformer<?> transformer, long startNanos) {
        if (startNanos == 0L || !TARGET.equals(className)) return;
        String owner = transformer instanceof TransformerHolder<?> holder ? holder.owner().name() : "unowned";
        long elapsed = System.nanoTime() - startNanos;
        System.err.printf("BOOTOPTIM_ML_FORK probe=%s class=%s stage=transformer owner=%s labels=%s elapsed_ns=%d thread=%s%n",
                "agent94-post-accept-v1", className, owner, Arrays.toString(transformer.labels()), elapsed,
                Thread.currentThread().getName());
    }
}
'''


def run(root: Path, *args: str) -> str:
    return subprocess.check_output(args, cwd=root, text=True).strip()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"anchor {label!r} expected once, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    root = args.source.resolve()

    head = run(root, "git", "rev-parse", "HEAD")
    if head != UPSTREAM_COMMIT:
        raise SystemExit(f"wrong ModLauncher commit: {head}; expected {UPSTREAM_COMMIT}")

    target = root / "src/main/java/cpw/mods/modlauncher/ClassTransformer.java"
    blob = run(root, "git", "hash-object", str(target))
    if blob != CLASS_TRANSFORMER_BLOB:
        raise SystemExit(f"ClassTransformer blob drift: {blob}; expected {CLASS_TRANSFORMER_BLOB}")

    text = target.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "        final EnumMap<ILaunchPluginService.Phase, List<ILaunchPluginService>> launchPluginTransformerSet = pluginHandler.computeLaunchPluginTransformerSet(classDesc, inputClass.length == 0, reason, this.auditTrail);",
        "        final long bootOptimPluginSelection = BootOptimForkTrace.begin(className);\n"
        "        final EnumMap<ILaunchPluginService.Phase, List<ILaunchPluginService>> launchPluginTransformerSet = pluginHandler.computeLaunchPluginTransformerSet(classDesc, inputClass.length == 0, reason, this.auditTrail);\n"
        "        BootOptimForkTrace.end(className, \"plugin_selection\", bootOptimPluginSelection);",
        "plugin selection",
    )
    text = replace_once(
        text,
        "        final int preFlags = pluginHandler.offerClassNodeToPlugins(ILaunchPluginService.Phase.BEFORE, launchPluginTransformerSet.getOrDefault(ILaunchPluginService.Phase.BEFORE, Collections.emptyList()), clazz, classDesc, auditTrail, reason);",
        "        final long bootOptimBeforePlugins = BootOptimForkTrace.begin(className);\n"
        "        final int preFlags = pluginHandler.offerClassNodeToPlugins(ILaunchPluginService.Phase.BEFORE, launchPluginTransformerSet.getOrDefault(ILaunchPluginService.Phase.BEFORE, Collections.emptyList()), clazz, classDesc, auditTrail, reason);\n"
        "        BootOptimForkTrace.end(className, \"plugins_before\", bootOptimBeforePlugins);",
        "before plugins",
    )
    text = replace_once(
        text,
        "        final int postFlags = pluginHandler.offerClassNodeToPlugins(ILaunchPluginService.Phase.AFTER, launchPluginTransformerSet.getOrDefault(ILaunchPluginService.Phase.AFTER, Collections.emptyList()), clazz, classDesc, auditTrail, reason);",
        "        final long bootOptimAfterPlugins = BootOptimForkTrace.begin(className);\n"
        "        final int postFlags = pluginHandler.offerClassNodeToPlugins(ILaunchPluginService.Phase.AFTER, launchPluginTransformerSet.getOrDefault(ILaunchPluginService.Phase.AFTER, Collections.emptyList()), clazz, classDesc, auditTrail, reason);\n"
        "        BootOptimForkTrace.end(className, \"plugins_after\", bootOptimAfterPlugins);",
        "after plugins",
    )
    text = replace_once(
        text,
        "        final ClassWriter cw = TransformerClassWriter.createClassWriter(mergedFlags, this, clazz);\n        clazz.accept(cw);",
        "        final long bootOptimWriterCreate = BootOptimForkTrace.begin(className);\n"
        "        final ClassWriter cw = TransformerClassWriter.createClassWriter(mergedFlags, this, clazz);\n"
        "        BootOptimForkTrace.end(className, \"writer_create\", bootOptimWriterCreate);\n"
        "        final long bootOptimWriterAccept = BootOptimForkTrace.begin(className);\n"
        "        clazz.accept(cw);\n"
        "        BootOptimForkTrace.end(className, \"writer_accept\", bootOptimWriterAccept);",
        "writer create/accept",
    )
    text = replace_once(
        text,
        "        return cw.toByteArray();",
        "        final long bootOptimToBytes = BootOptimForkTrace.begin(className);\n"
        "        final byte[] bootOptimResult = cw.toByteArray();\n"
        "        BootOptimForkTrace.end(className, \"writer_to_bytes\", bootOptimToBytes);\n"
        "        return bootOptimResult;",
        "final toByteArray",
    )
    text = replace_once(
        text,
        "                node = transformer.transform(node, context);\n                auditTrail.addTransformerAuditTrail(context.getClassName(), ((TransformerHolder<?>)transformer).owner(), transformer);",
        "                final long bootOptimTransformer = BootOptimForkTrace.begin(context.getClassName());\n"
        "                node = transformer.transform(node, context);\n"
        "                BootOptimForkTrace.transformer(context.getClassName(), transformer, bootOptimTransformer);\n"
        "                auditTrail.addTransformerAuditTrail(context.getClassName(), ((TransformerHolder<?>)transformer).owner(), transformer);",
        "transformer application",
    )
    target.write_text(text, encoding="utf-8")

    helper = root / "src/main/java/cpw/mods/modlauncher/BootOptimForkTrace.java"
    if helper.exists():
        raise SystemExit("probe helper already exists")
    helper.write_text(HELPER, encoding="utf-8")

    build = root / "build.gradle"
    provenance = f"""

// Agent 94 diagnostic artifact provenance. Does not alter module/class identities.
tasks.named('jar', Jar).configure {{
    manifest.attributes(
            'BootOptim-Fork-Probe': '{PROBE_ID}',
            'BootOptim-Upstream-Commit': '{UPSTREAM_COMMIT}')
}}
"""
    build.write_text(build.read_text(encoding="utf-8") + provenance, encoding="utf-8")

    print(f"patched ModLauncher {UPSTREAM_COMMIT} with {PROBE_ID}")


if __name__ == "__main__":
    main()
