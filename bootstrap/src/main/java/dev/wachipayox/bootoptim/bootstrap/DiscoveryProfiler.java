package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Low-overhead timing for FML discovery phases, active only during startup profiling. */
final class DiscoveryProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileStartup")
            || Boolean.getBoolean("boot_optim.benchmark.exitOnTitle");
    private static final AtomicLong ROOT_START = new AtomicLong();
    private static final AtomicLong DEPENDENCY_START = new AtomicLong();
    private static final AtomicLong ROOT_TASK = new AtomicLong();
    private static final AtomicLong DEPENDENCY_TASK = new AtomicLong();
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();

    private DiscoveryProfiler() {}

    static void beginRoot() {
        begin("root_mod_discovery", ROOT_START, ROOT_TASK);
    }

    static void endRoot() {
        end("root_mod_discovery", ROOT_START, ROOT_TASK);
    }

    static void beginDependencies() {
        begin("dependency_discovery", DEPENDENCY_START, DEPENDENCY_TASK);
    }

    static void endDependencies() {
        end("dependency_discovery", DEPENDENCY_START, DEPENDENCY_TASK);
    }

    private static void begin(String phase, AtomicLong holder, AtomicLong taskHolder) {
        if (!ENABLED && !TRACE.isEnabled()) {
            return;
        }
        long start = System.nanoTime();
        if (holder.compareAndSet(0L, start)) {
            taskHolder.compareAndSet(0L, TRACE.beginTask(phase, 0L, null, null, null, -1L));
            System.out.printf("BOOTOPTIM_STARTUP phase=%s_start uptime_ms=%d%n",
                    phase, ManagementFactory.getRuntimeMXBean().getUptime());
        }
    }

    private static void end(String phase, AtomicLong holder, AtomicLong taskHolder) {
        if (!ENABLED && !TRACE.isEnabled()) {
            return;
        }
        long start = holder.get();
        if (start == 0L) {
            return;
        }
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        long taskId = taskHolder.get();
        if (taskId != 0L) {
            TRACE.endTask(taskId, phase, -1L, "fml_discovery_end");
        }
        System.out.printf("BOOTOPTIM_STARTUP phase=%s_end uptime_ms=%d elapsed_ms=%.3f%n",
                phase, ManagementFactory.getRuntimeMXBean().getUptime(), elapsedMs);
    }
}
