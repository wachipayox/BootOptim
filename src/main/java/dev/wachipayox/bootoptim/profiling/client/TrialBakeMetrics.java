package dev.wachipayox.bootoptim.profiling.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Two thread CPU reads per complete bake, never per face/model/quad. */
public final class TrialBakeMetrics {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    public record Result(long wallNs, long cpuNs, int mask, boolean success) {}
    private static volatile Result result;
    private static volatile int calls;
    public static void reset() { result = null; calls = 0; }
    public static Result result() { return result; }
    public static int calls() { return calls; }
    public static void measure(Runnable original) {
        if (!TrialFeatureGate.ENABLED) { original.run(); return; }
        if (!THREADS.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("CPU clock unavailable");
        if (!THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        int mask = TrialFeatureGate.mask();
        long cpuStart = THREADS.getCurrentThreadCpuTime(), start = System.nanoTime();
        boolean success = false;
        try { original.run(); success = true; }
        finally {
            result = new Result(System.nanoTime() - start, THREADS.getCurrentThreadCpuTime() - cpuStart, mask, success);
            calls++;
        }
    }
}
