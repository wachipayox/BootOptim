package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Diagnostic only. Every recorded CPU interval includes observer cost; callback
 * CPU is subtracted from the original enclosing listResources interval.
 * Not a benchmark or a resource-result cache.
 */
public final class PathPackEnumerationProbe {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profilePathPackEnumeration");
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    private static final LongAdder LIST_CALLS = new LongAdder();
    private static final LongAdder LIST_FAILURES = new LongAdder();
    private static final LongAdder LIST_OUTPUTS = new LongAdder();
    private static final LongAdder LIST_CPU_NS = new LongAdder();
    private static final LongAdder LIST_CALLBACK_CPU_NS = new LongAdder();
    private static final LongAdder LIST_EXCLUSIVE_CPU_NS = new LongAdder();
    private static final LongAdder LIST_WALL_NS = new LongAdder();
    private static final LongAdder LIST_UNAVAILABLE = new LongAdder();
    private static final LongAdder NAMESPACE_CALLS = new LongAdder();
    private static final LongAdder NAMESPACE_FAILURES = new LongAdder();
    private static final LongAdder NAMESPACE_CPU_NS = new LongAdder();
    private static final LongAdder NAMESPACE_UNAVAILABLE = new LongAdder();
    private static final LongAdder INFLIGHT = new LongAdder();
    private static final Logger LOGGER = LogUtils.getLogger();

    private PathPackEnumerationProbe() {}

    public static long cpu() {
        return THREAD_CPU.isCurrentThreadCpuTimeSupported() && THREAD_CPU.isThreadCpuTimeEnabled()
                ? THREAD_CPU.getCurrentThreadCpuTime() : -1L;
    }

    public static void entering() { INFLIGHT.increment(); }

    public static void listCompleted(boolean success, long beforeCpu, long afterCpu,
            long callbackCpu, long outputs, long wallNs) {
        LIST_CALLS.increment();
        LIST_OUTPUTS.add(outputs);
        LIST_WALL_NS.add(wallNs);
        if (!success) LIST_FAILURES.increment();
        if (beforeCpu < 0 || afterCpu < 0 || afterCpu < beforeCpu || callbackCpu < 0) {
            LIST_UNAVAILABLE.increment();
        } else {
            long total = afterCpu - beforeCpu;
            LIST_CPU_NS.add(total);
            LIST_CALLBACK_CPU_NS.add(callbackCpu);
            // Negative differences indicate timer/observer contamination; do not turn into savings.
            LIST_EXCLUSIVE_CPU_NS.add(total - callbackCpu);
        }
        INFLIGHT.decrement();
    }

    public static void namespaceCompleted(boolean success, long beforeCpu, long afterCpu) {
        NAMESPACE_CALLS.increment();
        if (!success) NAMESPACE_FAILURES.increment();
        if (beforeCpu < 0 || afterCpu < 0 || afterCpu < beforeCpu) {
            NAMESPACE_UNAVAILABLE.increment();
        } else {
            NAMESPACE_CPU_NS.add(afterCpu - beforeCpu);
        }
        INFLIGHT.decrement();
    }

    public static void report() {
        if (!ENABLED) return;
        LOGGER.info("BOOTOPTIM_PATH_ENUM status=diagnostic_only list_calls={} list_failures={} list_outputs={} list_cpu_ns={} list_callback_cpu_ns={} list_exclusive_cpu_ns={} list_wall_ns={} list_cpu_unavailable={} namespace_calls={} namespace_failures={} namespace_cpu_ns={} namespace_cpu_unavailable={} inflight={} origin=hosted_exact_pack endpoint=main_menu",
                LIST_CALLS.sum(), LIST_FAILURES.sum(), LIST_OUTPUTS.sum(), LIST_CPU_NS.sum(),
                LIST_CALLBACK_CPU_NS.sum(), LIST_EXCLUSIVE_CPU_NS.sum(), LIST_WALL_NS.sum(),
                LIST_UNAVAILABLE.sum(), NAMESPACE_CALLS.sum(), NAMESPACE_FAILURES.sum(),
                NAMESPACE_CPU_NS.sum(), NAMESPACE_UNAVAILABLE.sum(), INFLIGHT.sum());
    }
}
