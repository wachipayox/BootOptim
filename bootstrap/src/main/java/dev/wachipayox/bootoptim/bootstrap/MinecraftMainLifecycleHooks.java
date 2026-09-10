package dev.wachipayox.bootoptim.bootstrap;

/** Diagnostic-only markers for the NeoForge Main -> BackgroundWaiter Bootstrap handoff. */
public final class MinecraftMainLifecycleHooks {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.mixinLifecycleTrace");

    private MinecraftMainLifecycleHooks() {}

    public static void mainEntry() { mark("main_entry"); }
    public static void beforeSharedConstantsVersion() { mark("before_shared_constants_version"); }
    public static void afterSharedConstantsVersion() { mark("after_shared_constants_version"); }
    public static void beforeDataFixersOptimize() { mark("before_datafixers_optimize"); }
    public static void afterDataFixersOptimize() { mark("after_datafixers_optimize"); }
    public static void beforeCrashReportPreload() { mark("before_crash_report_preload"); }
    public static void afterCrashReportPreload() { mark("after_crash_report_preload"); }
    public static void beforeRunAndTick() { mark("main_before_run_and_tick"); }
    public static void afterRunAndTick() { mark("main_after_run_and_tick"); }
    public static void bootstrapWorkerEntry() { mark("bootstrap_worker_entry"); }
    public static void bootstrapWorkerReturn() { mark("bootstrap_worker_return"); }
    public static void beforeDataFixerJoin() { mark("before_datafixer_join"); }
    public static void afterDataFixerJoin() { mark("after_datafixer_join"); }

    public static void dataFixersClinitEntry() { mark("datafixers_clinit_entry"); }
    public static void beforeCreateFixerUpperCall() { mark("datafixers_before_create_fixer_upper_call"); }
    public static void afterCreateFixerUpperCall() { mark("datafixers_after_create_fixer_upper_call"); }
    public static void dataFixersClinitExit() { mark("datafixers_clinit_exit"); }
    public static void createFixerUpperEntry() { mark("create_fixer_upper_entry"); }
    public static void createFixerUpperReturn() { mark("create_fixer_upper_return"); }
    public static void dataFixersOptimizeEntry() { mark("datafixers_optimize_entry"); }
    public static void beforeExecutorCreate() { mark("datafixers_before_executor_create"); }
    public static void afterExecutorCreate() { mark("datafixers_after_executor_create"); }
    public static void beforeResultGet() { mark("datafixers_before_result_get"); }
    public static void afterResultGet() { mark("datafixers_after_result_get"); }
    public static void beforeResultOptimize() { mark("datafixers_before_result_optimize"); }
    public static void afterResultOptimize() { mark("datafixers_after_result_optimize"); }
    public static void dataFixersOptimizeReturn() { mark("datafixers_optimize_return"); }

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
            // Diagnostics must never change launcher/game failure semantics.
        }
    }
}
