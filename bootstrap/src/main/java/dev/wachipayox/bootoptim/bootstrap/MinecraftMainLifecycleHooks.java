package dev.wachipayox.bootoptim.bootstrap;

/** Diagnostic-only markers for the NeoForge Main -> BackgroundWaiter Bootstrap handoff. */
public final class MinecraftMainLifecycleHooks {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.mixinLifecycleTrace");

    private MinecraftMainLifecycleHooks() {}

    public static void mainEntry() {
        mark("main_entry");
    }

    public static void beforeSharedConstantsVersion() {
        mark("before_shared_constants_version");
    }

    public static void afterSharedConstantsVersion() {
        mark("after_shared_constants_version");
    }

    public static void beforeDataFixersOptimize() {
        mark("before_datafixers_optimize");
    }

    public static void afterDataFixersOptimize() {
        mark("after_datafixers_optimize");
    }

    public static void beforeCrashReportPreload() {
        mark("before_crash_report_preload");
    }

    public static void afterCrashReportPreload() {
        mark("after_crash_report_preload");
    }

    public static void beforeRunAndTick() {
        mark("main_before_run_and_tick");
    }

    public static void afterRunAndTick() {
        mark("main_after_run_and_tick");
    }

    public static void bootstrapWorkerEntry() {
        mark("bootstrap_worker_entry");
    }

    public static void bootstrapWorkerReturn() {
        mark("bootstrap_worker_return");
    }

    private static void mark(String event) {
        if (!ENABLED) return;
        try {
            Thread thread = Thread.currentThread();
            ClassLoader tccl = thread.getContextClassLoader();
            System.err.printf(
                    "BOOTOPTIM_MAIN_LIFECYCLE event=%s mono_ns=%d thread=%s tccl=%s tccl_id=%d%n",
                    event,
                    System.nanoTime(),
                    thread.getName(),
                    tccl == null ? "bootstrap" : tccl.getClass().getName(),
                    tccl == null ? 0 : System.identityHashCode(tccl));
        } catch (Throwable ignored) {
            // Diagnostic must never change launcher/game failure semantics.
        }
    }
}
