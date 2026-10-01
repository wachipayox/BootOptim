package dev.wachipayox.bootoptim.bootstrap;

/** Diagnostic-only markers for the NeoForge Main -> BackgroundWaiter Bootstrap handoff. */
public final class MinecraftMainLifecycleHooks {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.mixinLifecycleTrace");
    private static volatile long VERSION_DETECTION_THREAD_ID = -1L;

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

    // Agent 101: nested, observational-only version detection boundaries. Reusing this already-entered
    // hook class avoids charging a new diagnostic hook class initialization to SharedConstants.<clinit>.
    public static void sharedConstantsClinitEnter() { mark("shared_constants_clinit_enter"); }
    public static void sharedConstantsClinitExit() { mark("shared_constants_clinit_exit"); }
    public static void sharedConstantsTryDetectEntry() { mark("shared_constants_try_detect_entry"); }
    public static void beforeDetectedVersionCall() {
        mark("before_detected_version_call");
        if (ENABLED) VERSION_DETECTION_THREAD_ID = Thread.currentThread().threadId();
    }
    public static void beforeVersionPublication() {
        mark("before_version_publication");
        VERSION_DETECTION_THREAD_ID = -1L;
    }
    public static void afterVersionPublication() { mark("after_version_publication"); }
    public static void sharedConstantsTryDetectExit() { mark("shared_constants_try_detect_exit"); }
    public static void detectedVersionClinitEnter() { mark("detected_version_clinit_enter"); }
    public static void detectedVersionClinitExit() { mark("detected_version_clinit_exit"); }
    public static void detectedVersionTryDetectEntry() { mark("detected_version_try_detect_entry"); }
    public static void beforeVersionResourceOpen() { mark("before_version_resource_open"); }
    public static void afterVersionResourceOpen() { mark("after_version_resource_open"); }
    public static void beforeJsonVersionExpression() { mark("before_json_version_expression"); }
    public static void afterJsonVersionConstruction() { mark("after_json_version_construction"); }
    public static void detectedVersionTryDetectExit() { mark("detected_version_try_detect_exit"); }
    public static void gsonHelperClinitEnter() { mark("gson_helper_clinit_enter"); }
    public static void gsonHelperClinitExit() { mark("gson_helper_clinit_exit"); }
    public static void gsonVersionParseEnter() {
        if (isVersionDetectionThread()) mark("gson_version_parse_enter");
    }
    public static void gsonVersionParseExit() {
        if (isVersionDetectionThread()) mark("gson_version_parse_exit");
    }

    private static boolean isVersionDetectionThread() {
        return ENABLED && Thread.currentThread().threadId() == VERSION_DETECTION_THREAD_ID;
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
