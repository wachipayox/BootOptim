package dev.wachipayox.bootoptim.bootstrap;

/** Diagnostic-only markers for the stock CrashReport.preload warmup path. */
public final class CrashReportPreloadProfileHooks {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.crashReportPreloadTrace");

    private CrashReportPreloadProfileHooks() {}

    public static void preloadEntry() { mark("preload_entry"); }
    public static void beforeMemoryReserve() { mark("before_memory_reserve"); }
    public static void afterMemoryReserve() { mark("after_memory_reserve"); }
    public static void beforeDummyConstruction() { mark("before_dummy_construction"); }
    public static void afterDummyConstruction() { mark("after_dummy_construction"); }
    public static void beforeFriendlyReport() { mark("before_friendly_report"); }
    public static void afterFriendlyReport() { mark("after_friendly_report"); }
    public static void preloadExit() { mark("preload_exit"); }
    public static void friendlyCoreEntry() { mark("friendly_core_entry"); }
    public static void beforeExceptionMessage() { mark("before_exception_message"); }
    public static void afterExceptionMessage() { mark("after_exception_message"); }
    public static void beforeDetails() { mark("before_details"); }
    public static void afterDetails() { mark("after_details"); }
    public static void friendlyCoreExit() { mark("friendly_core_exit"); }

    private static void mark(String event) {
        if (!ENABLED) return;
        try {
            Thread thread = Thread.currentThread();
            System.err.printf(
                    "BOOTOPTIM_CRASH_PRELOAD event=%s mono_ns=%d thread=%s thread_id=%d%n",
                    event,
                    System.nanoTime(),
                    thread.getName(),
                    thread.threadId());
        } catch (Throwable ignored) {
            // Diagnostic output must never replace or mask stock crash behavior.
        }
    }
}
