#!/usr/bin/env python3
"""Apply the Agent 94 diagnostic patch to exact ModLauncher 11.0.5 source."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

UPSTREAM_COMMIT = "901c6ea849ae21ee7d464cd97113e77a6101a734"
CLASS_TRANSFORMER_BLOB = "a0451dff688b78f075d0e79c3fba540361ba3304"
TRANSFORMING_CLASSLOADER_BLOB = "89343a57fdc88a4c1cfea7b33e9962d081662257"
PROBE_ID = "agent94-post-accept-v2"

HELPER = r'''/* Agent 94 diagnostic-only fork probe. */
package cpw.mods.modlauncher;

import cpw.mods.modlauncher.api.ITransformer;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class BootOptimForkTrace {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.modlauncherForkTrace");
    private static final String TARGET = "net.minecraft.server.Bootstrap";
    private static final AtomicBoolean IDENTITY = new AtomicBoolean();
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final ThreadLocal<ArrayDeque<Long>> REQUEST_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private BootOptimForkTrace() {}

    static boolean enabledFor(String className) {
        return ENABLED && TARGET.equals(className);
    }

    static long requestBegin(String className, String rawContext, String effectiveReason,
                             TransformingClassLoader loader, String targetModule) {
        if (!enabledFor(className)) return 0L;
        identity();
        final long id = REQUEST_IDS.incrementAndGet();
        try {
            final List<StackWalker.StackFrame> frames = WALKER.walk(stream -> stream.limit(16).toList());
            String origin = "other";
            for (var frame : frames) {
                if (frame.getClassName().equals("cpw.mods.cl.ModuleClassLoader")) {
                    if (frame.getMethodName().equals("readerToClass")) {
                        origin = "securejar_reader_to_class";
                        break;
                    }
                    if (frame.getMethodName().equals("getMaybeTransformedClassBytes")) {
                        origin = "securejar_get_maybe_transformed_bytes";
                    }
                }
            }
            StackWalker.StackFrame caller = frames.stream()
                    .filter(frame -> !isInfrastructure(frame.getClassName()))
                    .findFirst().orElse(null);
            String callerName = caller == null ? "none" : caller.getClassName() + "#" + caller.getMethodName();
            String callerModule = caller == null ? "none" : moduleName(caller.getDeclaringClass().getModule());
            String callerSource = caller == null ? "none" : codeSource(caller.getDeclaringClass());
            String stack = String.join(">", frames.stream()
                    .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                    .toList());
            ClassLoader parent = loader.getParent();
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            REQUEST_STACK.get().push(id);
            System.err.printf(
                    "BOOTOPTIM_ML_FORK_REQUEST probe=agent94-post-accept-v2 request_id=%d class=%s origin=%s raw_context=%s effective_reason=%s thread=%s loader_class=%s loader_name=%s loader_id=%d loader_module=%s loader_source=%s parent_class=%s parent_name=%s parent_id=%d target_module=%s tccl_class=%s tccl_name=%s tccl_id=%d caller=%s caller_module=%s caller_source=%s stack=%s%n",
                    id, className, safe(origin), safe(rawContext), safe(effectiveReason), safe(Thread.currentThread().getName()),
                    safe(loader.getClass().getName()), safe(loader.getName()), System.identityHashCode(loader),
                    safe(moduleName(loader.getClass().getModule())), safe(codeSource(loader.getClass())),
                    safe(parent == null ? "none" : parent.getClass().getName()), safe(parent == null ? "none" : parent.getName()),
                    parent == null ? 0 : System.identityHashCode(parent), safe(targetModule),
                    safe(tccl == null ? "none" : tccl.getClass().getName()), safe(tccl == null ? "none" : tccl.getName()),
                    tccl == null ? 0 : System.identityHashCode(tccl), safe(callerName), safe(callerModule), safe(callerSource), safe(stack));
            return id;
        } catch (Throwable throwable) {
            System.err.printf("BOOTOPTIM_ML_FORK_REQUEST_ERROR probe=agent94-post-accept-v2 request_id=%d class=%s error=%s%n",
                    id, className, safe(throwable.getClass().getName()));
            return 0L;
        }
    }

    static void requestEnd(String className, long requestId) {
        if (requestId == 0L || !TARGET.equals(className)) return;
        try {
            ArrayDeque<Long> stack = REQUEST_STACK.get();
            long current = stack.isEmpty() ? 0L : stack.pop();
            System.err.printf("BOOTOPTIM_ML_FORK_REQUEST_END probe=agent94-post-accept-v2 request_id=%d observed_id=%d class=%s mono_ns=%d thread=%s%n",
                    requestId, current, className, System.nanoTime(), safe(Thread.currentThread().getName()));
            if (stack.isEmpty()) REQUEST_STACK.remove();
        } catch (Throwable throwable) {
            System.err.printf("BOOTOPTIM_ML_FORK_REQUEST_ERROR probe=agent94-post-accept-v2 request_id=%d class=%s error=%s%n",
                    requestId, className, safe(throwable.getClass().getName()));
        }
    }

    static long begin(String className) {
        if (!enabledFor(className)) return 0L;
        identity();
        return System.nanoTime();
    }

    static void point(String className, String stage) {
        if (!enabledFor(className)) return;
        identity();
        long now = System.nanoTime();
        System.err.printf("BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=%d class=%s stage=%s mono_ns=%d thread=%s%n",
                currentRequestId(), className, stage, now, safe(Thread.currentThread().getName()));
    }

    static void end(String className, String stage, long startNanos) {
        if (startNanos == 0L || !TARGET.equals(className)) return;
        long end = System.nanoTime();
        System.err.printf("BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=%d class=%s stage=%s start_ns=%d end_ns=%d elapsed_ns=%d thread=%s%n",
                currentRequestId(), className, stage, startNanos, end, end - startNanos, safe(Thread.currentThread().getName()));
    }

    static void transformer(String className, ITransformer<?> transformer, long startNanos) {
        if (startNanos == 0L || !TARGET.equals(className)) return;
        String owner = transformer instanceof TransformerHolder<?> holder ? holder.owner().name() : "unowned";
        long end = System.nanoTime();
        System.err.printf("BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=%d class=%s stage=transformer owner=%s labels=%s start_ns=%d end_ns=%d elapsed_ns=%d thread=%s%n",
                currentRequestId(), className, safe(owner), Arrays.toString(transformer.labels()), startNanos, end, end - startNanos,
                safe(Thread.currentThread().getName()));
    }

    private static long currentRequestId() {
        ArrayDeque<Long> stack = REQUEST_STACK.get();
        return stack.isEmpty() ? 0L : stack.peek();
    }

    private static boolean isInfrastructure(String className) {
        return className.startsWith("cpw.mods.modlauncher.")
                || className.startsWith("cpw.mods.cl.")
                || className.startsWith("java.")
                || className.startsWith("jdk.");
    }

    private static String moduleName(Module module) {
        String name = module == null ? null : module.getName();
        return name == null ? "unnamed" : name;
    }

    private static String codeSource(Class<?> type) {
        try {
            if (type == null || type.getProtectionDomain() == null) return "none";
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? "none" : source.getLocation().toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String safe(String value) {
        if (value == null) return "null";
        return value.replace(' ', '_').replace('\t', '_').replace('\r', '_').replace('\n', '_');
    }

    private static void identity() {
        if (!IDENTITY.compareAndSet(false, true)) return;
        Module module = BootOptimForkTrace.class.getModule();
        var source = BootOptimForkTrace.class.getProtectionDomain().getCodeSource();
        String location = source == null ? "none" : String.valueOf(source.getLocation());
        System.err.printf("BOOTOPTIM_ML_FORK_IDENTITY probe=agent94-post-accept-v2 module=%s named=%s source=%s implementation=%s loader=%s%n",
                module.getName(), module.isNamed(), safe(location),
                BootOptimForkTrace.class.getPackage().getImplementationVersion(),
                safe(String.valueOf(BootOptimForkTrace.class.getClassLoader())));
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

    loader_target = root / "src/main/java/cpw/mods/modlauncher/TransformingClassLoader.java"
    loader_blob = run(root, "git", "hash-object", str(loader_target))
    if loader_blob != TRANSFORMING_CLASSLOADER_BLOB:
        raise SystemExit(f"TransformingClassLoader blob drift: {loader_blob}; expected {TRANSFORMING_CLASSLOADER_BLOB}")

    text = target.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "        final String internalName = className.replace('.', '/');",
        "        BootOptimForkTrace.point(className, \"class_transform_begin\");\n"
        "        final String internalName = className.replace('.', '/');",
        "transform begin",
    )
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
        "        BootOptimForkTrace.point(className, \"class_transform_return\");\n"
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

    loader_text = loader_target.read_text(encoding="utf-8")
    loader_text = replace_once(
        loader_text,
        "    protected byte[] maybeTransformClassBytes(final byte[] bytes, final String name, final String context) {\n        return classTransformer.transform(bytes, name, context != null ? context : ITransformerActivity.CLASSLOADING_REASON);\n    }",
        "    protected byte[] maybeTransformClassBytes(final byte[] bytes, final String name, final String context) {\n"
        "        final String reason = context != null ? context : ITransformerActivity.CLASSLOADING_REASON;\n"
        "        if (!BootOptimForkTrace.enabledFor(name)) {\n"
        "            return classTransformer.transform(bytes, name, reason);\n"
        "        }\n"
        "        final long requestId = BootOptimForkTrace.requestBegin(name, context, reason, this, this.classNameToModuleName(name));\n"
        "        try {\n"
        "            return classTransformer.transform(bytes, name, reason);\n"
        "        } finally {\n"
        "            BootOptimForkTrace.requestEnd(name, requestId);\n"
        "        }\n"
        "    }",
        "transform request origin",
    )
    loader_target.write_text(loader_text, encoding="utf-8")

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
    print(f"patched ModLauncher {UPSTREAM_COMMIT} with {PROBE_ID} + request-origin trace")


if __name__ == "__main__":
    main()
