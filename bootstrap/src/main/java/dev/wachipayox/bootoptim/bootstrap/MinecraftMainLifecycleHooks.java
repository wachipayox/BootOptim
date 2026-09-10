package dev.wachipayox.bootoptim.bootstrap;

/** Diagnostic-only markers for the NeoForge Main -> BackgroundWaiter Bootstrap handoff. */
public final class MinecraftMainLifecycleHooks {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.mixinLifecycleTrace");

    private static long schemaStartNs;
    private static long schemaTotalNs;
    private static long schemaFirstNs;
    private static long schemaSecondNs;
    private static long schemaRestNs;
    private static int schemaCalls;
    private static long fixerRegisterStartNs;
    private static long fixerRegisterTotalNs;
    private static int fixerRegisterCalls;

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
    public static void beforeDataFixerBuilderConstructor() { mark("create_before_builder_constructor"); }
    public static void afterDataFixerBuilderConstructor() { mark("create_after_builder_constructor"); }
    public static void beforeAddFixers() { mark("create_before_add_fixers"); }
    public static void afterAddFixers() { mark("create_after_add_fixers"); }
    public static void beforeDataFixerBuilderBuild() { mark("create_before_builder_build"); }
    public static void afterDataFixerBuilderBuild() { mark("create_after_builder_build"); }
    public static void createFixerUpperReturn() {
        mark("create_fixer_upper_return");
        emitDataFixerCreateSummary();
    }
    public static void dataFixersOptimizeEntry() { mark("datafixers_optimize_entry"); }
    public static void beforeExecutorCreate() { mark("datafixers_before_executor_create"); }
    public static void afterExecutorCreate() { mark("datafixers_after_executor_create"); }
    public static void beforeResultGet() { mark("datafixers_before_result_get"); }
    public static void afterResultGet() { mark("datafixers_after_result_get"); }
    public static void beforeResultOptimize() { mark("datafixers_before_result_optimize"); }
    public static void afterResultOptimize() { mark("datafixers_after_result_optimize"); }
    public static void dataFixersOptimizeReturn() { mark("datafixers_optimize_return"); }

    public static void beforeSchemaAdd() {
        if (!ENABLED) return;
        schemaStartNs = System.nanoTime();
    }

    public static void afterSchemaAdd() {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - schemaStartNs;
        schemaTotalNs += elapsed;
        schemaCalls++;
        if (schemaCalls == 1) schemaFirstNs = elapsed;
        else if (schemaCalls == 2) schemaSecondNs = elapsed;
        else schemaRestNs += elapsed;
    }

    public static void beforeFixerRegister() {
        if (!ENABLED) return;
        fixerRegisterStartNs = System.nanoTime();
    }

    public static void afterFixerRegister() {
        if (!ENABLED) return;
        fixerRegisterTotalNs += System.nanoTime() - fixerRegisterStartNs;
        fixerRegisterCalls++;
    }

    private static void emitDataFixerCreateSummary() {
        if (!ENABLED) return;
        try {
            Thread thread = Thread.currentThread();
            ClassLoader tccl = thread.getContextClassLoader();
            System.err.printf(
                    "BOOTOPTIM_DATAFIXER_CREATE_SUBPHASES schema_calls=%d schema_total_ns=%d schema_first_ns=%d schema_second_ns=%d schema_rest_ns=%d fixer_register_calls=%d fixer_register_total_ns=%d thread=%s tccl=%s tccl_id=%d%n",
                    schemaCalls,
                    schemaTotalNs,
                    schemaFirstNs,
                    schemaSecondNs,
                    schemaRestNs,
                    fixerRegisterCalls,
                    fixerRegisterTotalNs,
                    thread.getName(),
                    tccl == null ? "bootstrap" : tccl.getClass().getName(),
                    tccl == null ? 0 : System.identityHashCode(tccl));
        } catch (Throwable ignored) {
            // Diagnostics must never change launcher/game failure semantics.
        }
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
            // Diagnostics must never change launcher/game failure semantics.
        }
    }
}
