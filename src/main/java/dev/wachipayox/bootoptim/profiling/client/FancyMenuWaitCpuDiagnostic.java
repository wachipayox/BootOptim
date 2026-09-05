package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * Diagnostic-only attribution for FancyMenu's stock preload waits.
 *
 * <p>The mixin boundary calls {@link #beforeWait(Family)} immediately before FancyMenu invokes
 * {@code waitForLoadingCompletedOrFailed(long)} and {@link #afterWait(Family)} immediately after
 * that invocation returns. This class never replaces, cancels, sleeps, parks, or otherwise changes
 * the stock wait.</p>
 */
public final class FancyMenuWaitCpuDiagnostic {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROPERTY = "boot_optim.fancymenuWaitCpuDiagnostic";
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private static final long[] WAIT_CALLS = new long[Family.values().length];
    private static final long[] WAIT_WALL_NANOS = new long[Family.values().length];
    private static final long[] WAIT_CPU_NANOS = new long[Family.values().length];

    private static boolean consumed;
    private static boolean active;
    private static Thread ownerThread;
    private static boolean cpuTimeAvailable;
    private static boolean cpuMeasurementFailed;
    private static boolean boundaryInvalid;
    private static int waitDepth;
    private static long nestedWaits;
    private static Family activeFamily;
    private static long waitWallStartNanos;
    private static long waitCpuStartNanos;
    private static long preloadWallStartNanos;
    private static long preloadCpuStartNanos;

    private FancyMenuWaitCpuDiagnostic() {}

    public enum Family {
        ORDINARY,
        SLIDESHOW,
        PANORAMA
    }

    public static void beginPreLoad() {
        if (!Boolean.getBoolean(PROPERTY) || consumed) {
            return;
        }

        consumed = true;
        active = true;
        ownerThread = Thread.currentThread();
        boundaryInvalid = false;
        cpuMeasurementFailed = false;
        waitDepth = 0;
        nestedWaits = 0L;
        activeFamily = null;
        for (int i = 0; i < WAIT_CALLS.length; i++) {
            WAIT_CALLS[i] = 0L;
            WAIT_WALL_NANOS[i] = 0L;
            WAIT_CPU_NANOS[i] = 0L;
        }

        cpuTimeAvailable = enableCurrentThreadCpuTime();
        preloadCpuStartNanos = currentThreadCpuTime();
        if (preloadCpuStartNanos < 0L) {
            cpuTimeAvailable = false;
        }
        preloadWallStartNanos = System.nanoTime();
    }

    public static void beforeWait(Family family) {
        if (!ownedActiveScope()) {
            return;
        }

        waitDepth++;
        if (waitDepth != 1) {
            nestedWaits++;
            boundaryInvalid = true;
            return;
        }

        activeFamily = family;
        waitCpuStartNanos = currentThreadCpuTime();
        if (cpuTimeAvailable && waitCpuStartNanos < 0L) {
            cpuMeasurementFailed = true;
        }
        waitWallStartNanos = System.nanoTime();
    }

    public static void afterWait(Family family) {
        if (!ownedActiveScope()) {
            return;
        }
        if (waitDepth <= 0) {
            boundaryInvalid = true;
            return;
        }
        if (waitDepth > 1) {
            waitDepth--;
            return;
        }

        long wallEndNanos = System.nanoTime();
        long cpuEndNanos = currentThreadCpuTime();
        if (activeFamily != family) {
            boundaryInvalid = true;
        }

        int index = family.ordinal();
        WAIT_CALLS[index]++;
        WAIT_WALL_NANOS[index] += Math.max(0L, wallEndNanos - waitWallStartNanos);
        if (cpuTimeAvailable && waitCpuStartNanos >= 0L && cpuEndNanos >= 0L) {
            WAIT_CPU_NANOS[index] += Math.max(0L, cpuEndNanos - waitCpuStartNanos);
        } else if (cpuTimeAvailable) {
            cpuMeasurementFailed = true;
        }

        waitDepth = 0;
        activeFamily = null;
    }

    public static void finishPreLoad() {
        if (!ownedActiveScope()) {
            return;
        }

        long preloadWallEndNanos = System.nanoTime();
        long preloadCpuEndNanos = currentThreadCpuTime();
        if (waitDepth != 0) {
            boundaryInvalid = true;
        }
        if (cpuTimeAvailable && preloadCpuEndNanos < 0L) {
            cpuMeasurementFailed = true;
        }

        long totalCalls = 0L;
        for (long calls : WAIT_CALLS) {
            totalCalls += calls;
        }

        String status;
        if (boundaryInvalid) {
            status = "boundary_invalid";
        } else if (totalCalls == 0L) {
            status = "zero_coverage";
        } else if (!cpuTimeAvailable || cpuMeasurementFailed) {
            status = "cpu_unavailable";
        } else {
            status = "ok";
        }

        long preloadCpuNanos = cpuTimeAvailable && !cpuMeasurementFailed
                ? Math.max(0L, preloadCpuEndNanos - preloadCpuStartNanos)
                : -1L;
        LOGGER.info(
                "BOOTOPTIM_FANCYMENU_WAIT_CPU status={} wait_calls={} nested_waits={} preload_wall_ms={} preload_cpu_ms={} ordinary_calls={} ordinary_wall_ms={} ordinary_cpu_ms={} slideshow_calls={} slideshow_wall_ms={} slideshow_cpu_ms={} panorama_calls={} panorama_wall_ms={} panorama_cpu_ms={}",
                status,
                totalCalls,
                nestedWaits,
                formatMillis(Math.max(0L, preloadWallEndNanos - preloadWallStartNanos)),
                formatMillis(preloadCpuNanos),
                WAIT_CALLS[Family.ORDINARY.ordinal()],
                formatMillis(WAIT_WALL_NANOS[Family.ORDINARY.ordinal()]),
                formatCpuMillis(Family.ORDINARY),
                WAIT_CALLS[Family.SLIDESHOW.ordinal()],
                formatMillis(WAIT_WALL_NANOS[Family.SLIDESHOW.ordinal()]),
                formatCpuMillis(Family.SLIDESHOW),
                WAIT_CALLS[Family.PANORAMA.ordinal()],
                formatMillis(WAIT_WALL_NANOS[Family.PANORAMA.ordinal()]),
                formatCpuMillis(Family.PANORAMA));

        active = false;
        ownerThread = null;
        waitDepth = 0;
        activeFamily = null;
    }

    private static boolean ownedActiveScope() {
        return active && Thread.currentThread() == ownerThread;
    }

    private static boolean enableCurrentThreadCpuTime() {
        if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()) {
            return false;
        }
        try {
            if (!THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
            }
            return THREAD_MX_BEAN.isThreadCpuTimeEnabled();
        } catch (SecurityException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    private static long currentThreadCpuTime() {
        if (!cpuTimeAvailable) {
            return -1L;
        }
        try {
            return THREAD_MX_BEAN.getCurrentThreadCpuTime();
        } catch (UnsupportedOperationException ignored) {
            return -1L;
        }
    }

    private static String formatCpuMillis(Family family) {
        return cpuTimeAvailable && !cpuMeasurementFailed
                ? formatMillis(WAIT_CPU_NANOS[family.ordinal()])
                : "na";
    }

    private static String formatMillis(long nanos) {
        if (nanos < 0L) {
            return "na";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }
}
