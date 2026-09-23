package dev.wachipayox.bootoptim.bootstrap;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Low-overhead timing for FML discovery phases, active only during startup profiling. */
final class DiscoveryProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileStartup")
            || Boolean.getBoolean("boot_optim.benchmark.exitOnTitle")
            || BootstrapVarianceProbe.enabled();
    private static final AtomicLong ROOT_START = new AtomicLong();
    private static final AtomicLong DEPENDENCY_START = new AtomicLong();
    private static final AtomicReference<BootstrapVarianceProbe.Stamp> ROOT_VARIANCE = new AtomicReference<>();
    private static final AtomicReference<BootstrapVarianceProbe.Stamp> DEPENDENCY_VARIANCE = new AtomicReference<>();

    private DiscoveryProfiler() {}

    static void beginRoot() {
        begin("root_mod_discovery", ROOT_START, ROOT_VARIANCE);
    }

    static void endRoot() {
        end("root_mod_discovery", ROOT_START, ROOT_VARIANCE);
    }

    static void beginDependencies() {
        begin("dependency_discovery", DEPENDENCY_START, DEPENDENCY_VARIANCE);
    }

    static void endDependencies() {
        end("dependency_discovery", DEPENDENCY_START, DEPENDENCY_VARIANCE);
    }

    private static void begin(
            String phase,
            AtomicLong holder,
            AtomicReference<BootstrapVarianceProbe.Stamp> varianceHolder) {
        if (!ENABLED) {
            return;
        }
        long start = System.nanoTime();
        if (holder.compareAndSet(0L, start)) {
            varianceHolder.compareAndSet(null, BootstrapVarianceProbe.start(phase));
            System.out.printf("BOOTOPTIM_STARTUP phase=%s_start uptime_ms=%d%n",
                    phase, ManagementFactory.getRuntimeMXBean().getUptime());
        }
    }

    private static void end(
            String phase,
            AtomicLong holder,
            AtomicReference<BootstrapVarianceProbe.Stamp> varianceHolder) {
        if (!ENABLED) {
            return;
        }
        long start = holder.get();
        if (start == 0L) {
            return;
        }
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("BOOTOPTIM_STARTUP phase=%s_end uptime_ms=%d elapsed_ms=%.3f%n",
                phase, ManagementFactory.getRuntimeMXBean().getUptime(), elapsedMs);
        BootstrapVarianceProbe.finish(phase, varianceHolder.getAndSet(null));
    }
}
