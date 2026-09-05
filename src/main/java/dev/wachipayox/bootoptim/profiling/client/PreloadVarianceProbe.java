package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.util.Locale;

/** Benchmark-only: three snapshots, no polling, no worker/resource instrumentation. */
public final class PreloadVarianceProbe {
    private static Snapshot preloadStart;
    private static Snapshot preloadEnd;
    private static boolean finished;

    private PreloadVarianceProbe() {}

    public static void begin() {
        if (!Boolean.getBoolean("boot_optim.fancymenuWaitCpuDiagnostic")) return;
        preloadStart = snapshot();
    }

    public static void end() {
        if (preloadStart == null) return;
        preloadEnd = snapshot();
        report("preload", preloadStart, preloadEnd);
    }

    public static void title() {
        if (finished || preloadEnd == null) return;
        finished = true;
        report("after_preload_to_title", preloadEnd, snapshot());
    }

    private static Snapshot snapshot() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            long cpu = -1L;
            long available = -1L;
            if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
                cpu = extended.getProcessCpuTime();
                available = extended.getFreeMemorySize();
            }
            long gcMillis = 0L;
            long gcCount = 0L;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long time = gc.getCollectionTime();
                long count = gc.getCollectionCount();
                if (time < 0 || count < 0) { gcMillis = -1L; gcCount = -1L; break; }
                gcMillis += time;
                gcCount += count;
            }
            return new Snapshot(System.nanoTime(), cpu, gcMillis, gcCount,
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), available);
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("BOOTOPTIM_PRELOAD_VARIANCE status=snapshot_failed", failure);
            return null;
        }
    }

    private static void report(String phase, Snapshot start, Snapshot end) {
        if (start == null || end == null) return;
        LogUtils.getLogger().info(
                "BOOTOPTIM_PRELOAD_VARIANCE phase={} wall_ms={} process_cpu_ms={} gc_elapsed_ms={} gc_count={} heap_start_mib={} heap_end_mib={} available_start_mib={} available_end_mib={}",
                phase, ms(end.nanoTime - start.nanoTime), deltaMillis(start.processCpu, end.processCpu),
                delta(start.gcMillis, end.gcMillis), delta(start.gcCount, end.gcCount),
                mib(start.heap), mib(end.heap), mib(start.available), mib(end.available));
    }

    private static String deltaMillis(long a, long b) { return a < 0 || b < a ? "na" : ms(b - a); }
    private static long delta(long a, long b) { return a < 0 || b < a ? -1L : b - a; }
    private static String ms(long value) { return String.format(Locale.ROOT, "%.3f", value / 1_000_000.0D); }
    private static long mib(long value) { return value < 0 ? -1L : value / (1024L * 1024L); }
    private record Snapshot(long nanoTime, long processCpu, long gcMillis, long gcCount, long heap, long available) {}
}
