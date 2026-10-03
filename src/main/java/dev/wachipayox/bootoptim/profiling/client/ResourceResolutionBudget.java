package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.resources.ResourceLocation;

/** Diagnostic only: no resource, supplier, stream, or callback result is cached. */
public final class ResourceResolutionBudget {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileResourceResolution");
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final ConcurrentHashMap<Key, LongAdder> QUERIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Provider> PROVIDERS = new ConcurrentHashMap<>();
    private static final AtomicLong ORDINAL = new AtomicLong();
    private static final AtomicLong INFLIGHT = new AtomicLong();
    private static final AtomicBoolean CLOSED = new AtomicBoolean();
    private static final LongAdder CALLS = new LongAdder(), ROOTS = new LongAdder(), HITS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder(), SAMPLES = new LongAdder(), CPU_SAMPLES = new LongAdder();
    private static final LongAdder ROOT_CPU = new LongAdder(), ROOT_WALL = new LongAdder(), CHILD_CPU = new LongAdder();
    private static volatile boolean truncated;

    private ResourceResolutionBudget() {}

    private record Key(Object manager, ResourceLocation location, String mode) {
        @Override public int hashCode() {
            return 31 * System.identityHashCode(manager) + 17 * location.hashCode() + mode.hashCode();
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key k && manager == k.manager && location.equals(k.location) && mode.equals(k.mode);
        }
    }

    private static final class Provider {
        final LongAdder calls = new LongAdder(), hits = new LongAdder(), failures = new LongAdder();
        final LongAdder samples = new LongAdder(), cpuSamples = new LongAdder(), cpu = new LongAdder(), wall = new LongAdder();
    }

    public static final class Scope {
        private final Scope parent;
        private final boolean sampled;
        private final long cpu, wall;
        private Scope(Scope parent, boolean sampled) {
            this.parent = parent;
            this.sampled = sampled;
            this.wall = sampled ? System.nanoTime() : 0;
            this.cpu = sampled ? cpuNow() : -1;
        }
    }

    public static Scope begin(Object manager, ResourceLocation location, String mode) {
        if (!ENABLED || CLOSED.get() || location == null) return null;
        INFLIGHT.incrementAndGet();
        Scope parent = ACTIVE.get();
        CALLS.increment();
        if (parent == null) ROOTS.increment();
        Key key = new Key(manager, location, mode);
        LongAdder count = QUERIES.get(key);
        if (count != null) count.increment();
        else if (QUERIES.size() < 200_000) QUERIES.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        else truncated = true;
        // Systematic sample of ROOT requests only. Never extrapolate it as exact total CPU.
        boolean sampled = parent == null && (ORDINAL.getAndIncrement() & 15) == 0;
        Scope scope = new Scope(parent, sampled);
        ACTIVE.set(scope);
        return scope;
    }

    public static void finish(Scope scope, boolean success, int outputs) {
        if (scope == null) return;
        long cpuEnd = scope.sampled ? cpuNow() : -1;
        long wallEnd = scope.sampled ? System.nanoTime() : 0;
        ACTIVE.set(scope.parent);
        if (success && outputs > 0) HITS.increment();
        if (!success) FAILURES.increment();
        if (scope.sampled) {
            SAMPLES.increment();
            ROOT_WALL.add(wallEnd - scope.wall);
            if (scope.cpu >= 0 && cpuEnd >= scope.cpu) {
                CPU_SAMPLES.increment();
                ROOT_CPU.add(cpuEnd - scope.cpu);
            }
        }
        INFLIGHT.decrementAndGet();
    }

    public static Scope active() { return ACTIVE.get(); }
    public static boolean sampled(Scope scope) { return scope != null && scope.sampled; }

    public static long cpuNow() {
        try {
            return CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled()
                    ? CPU.getCurrentThreadCpuTime() : -1;
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            return -1;
        }
    }

    public static void provider(Scope scope, String providerClass, boolean success, boolean hit,
                                long cpuStart, long cpuEnd, long wallStart, long wallEnd) {
        if (scope == null) return;
        Provider row = PROVIDERS.computeIfAbsent(providerClass, ignored -> new Provider());
        row.calls.increment();
        if (hit) row.hits.increment();
        if (!success) row.failures.increment();
        if (scope.sampled) {
            row.samples.increment();
            row.wall.add(wallEnd - wallStart);
            if (cpuStart >= 0 && cpuEnd >= cpuStart) {
                row.cpuSamples.increment();
                row.cpu.add(cpuEnd - cpuStart);
                CHILD_CPU.add(cpuEnd - cpuStart);
            }
        }
    }

    public static void report() {
        if (!ENABLED || !CLOSED.compareAndSet(false, true)) return;
        var logger = LogUtils.getLogger();
        long repetitions = QUERIES.values().stream().mapToLong(c -> Math.max(0, c.sum() - 1)).sum();
        logger.info("BOOTOPTIM_RESOLUTION status=snapshot calls={} roots={} unique={} repeats={} hits={} failures={} sampled_roots={} cpu_samples={} sampled_cpu_ns={} sampled_wall_ns={} sampled_provider_cpu_ns={} inflight={} truncated={}",
                CALLS.sum(), ROOTS.sum(), QUERIES.size(), repetitions, HITS.sum(), FAILURES.sum(),
                SAMPLES.sum(), CPU_SAMPLES.sum(), ROOT_CPU.sum(), ROOT_WALL.sum(), CHILD_CPU.sum(), INFLIGHT.get(), truncated);
        PROVIDERS.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            Provider p = entry.getValue();
            logger.info("BOOTOPTIM_RESOLUTION_PROVIDER class={} calls={} hits={} failures={} samples={} cpu_samples={} sampled_cpu_ns={} sampled_wall_ns={}",
                    entry.getKey(), p.calls.sum(), p.hits.sum(), p.failures.sum(), p.samples.sum(),
                    p.cpuSamples.sum(), p.cpu.sum(), p.wall.sum());
        });
        // Counts retain manager identities only until this diagnostic snapshot.
        if (INFLIGHT.get() == 0) { QUERIES.clear(); PROVIDERS.clear(); }
    }
}
