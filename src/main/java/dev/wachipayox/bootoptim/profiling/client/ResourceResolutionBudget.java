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
    private static final ConcurrentHashMap<Key, Query> QUERIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Query> FAMILIES = new ConcurrentHashMap<>();
    private static final boolean TRACE_CALLERS = Boolean.getBoolean("boot_optim.profileResourceResolutionCallers");
    private static final ConcurrentHashMap<String, LongAdder> CALLERS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();
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

    private static final class Query {
        final AtomicLong count = new AtomicLong();
        final LongAdder repeats = new LongAdder(), hits = new LongAdder(), failures = new LongAdder(), probes = new LongAdder();
        final LongAdder samples = new LongAdder(), cpuSamples = new LongAdder(), cpu = new LongAdder(), wall = new LongAdder();
        final LongAdder firstCpu = new LongAdder(), repeatCpu = new LongAdder();
    }

    public static final class Scope {
        private final Scope parent;
        private final boolean sampled;
        private final Query query, family;
        private final boolean repeated;
        private final long cpu, wall;
        private int probes;
        private Scope(Scope parent, boolean sampled, Query query, Query family, boolean repeated) {
            this.parent = parent;
            this.sampled = sampled;
            this.query = query;
            this.family = family;
            this.repeated = repeated;
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
        Query query = QUERIES.get(key);
        if (query == null && QUERIES.size() < 200_000) query = QUERIES.computeIfAbsent(key, ignored -> new Query());
        boolean repeated = query != null && query.count.getAndIncrement() > 0;
        if (query != null && repeated) query.repeats.increment();
        if (query == null) truncated = true;
        String path = location.getPath();
        int slash = path.indexOf('/');
        String familyKey = location.getNamespace() + ":" + (slash < 0 ? "<root>" : path.substring(0, slash)) + "/" + mode;
        Query family = FAMILIES.get(familyKey);
        if (family == null && FAMILIES.size() < 2_000) family = FAMILIES.computeIfAbsent(familyKey, ignored -> new Query());
        if (family != null) {
            family.count.incrementAndGet();
            if (repeated) family.repeats.increment();
        } else truncated = true;
        // Whiten the sequence to avoid locking onto periodic resource-query loops.
        // Expected 1/16 ROOT requests; NEVER extrapolate as exact total CPU.
        long roll = mix(ORDINAL.getAndIncrement());
        boolean sampled = parent == null && (roll & 15) == 0;
        if (TRACE_CALLERS && parent == null && (roll & 255) == 0) {
            String trace = familyKey + "|" + WALKER.walk(frames -> frames
                    .filter(frame -> !frame.getClassName().equals(ResourceResolutionBudget.class.getName()))
                    .limit(16).map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                    .collect(java.util.stream.Collectors.joining(">")));
            LongAdder visits = CALLERS.get(trace);
            if (visits == null && CALLERS.size() < 200) visits = CALLERS.computeIfAbsent(trace, ignored -> new LongAdder());
            if (visits != null) visits.increment(); else truncated = true;
        }
        Scope scope = new Scope(parent, sampled, query, family, repeated);
        ACTIVE.set(scope);
        return scope;
    }

    private static long mix(long value) {
        value += 0x9e3779b97f4a7c15L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public static void finish(Scope scope, boolean success, int outputs) {
        if (scope == null) return;
        long cpuEnd = scope.sampled ? cpuNow() : -1;
        long wallEnd = scope.sampled ? System.nanoTime() : 0;
        ACTIVE.set(scope.parent);
        if (success && outputs > 0) HITS.increment();
        if (!success) FAILURES.increment();
        long cpuDelta = scope.cpu >= 0 && cpuEnd >= scope.cpu ? cpuEnd - scope.cpu : -1;
        updateQuery(scope.query, scope, success, outputs, cpuDelta, wallEnd);
        updateQuery(scope.family, scope, success, outputs, cpuDelta, wallEnd);
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

    private static void updateQuery(Query query, Scope scope, boolean success, int outputs, long cpuDelta, long wallEnd) {
        if (query == null) return;
        if (success && outputs > 0) query.hits.increment();
        if (!success) query.failures.increment();
        query.probes.add(scope.probes);
        if (scope.sampled) {
            query.samples.increment();
            query.wall.add(wallEnd - scope.wall);
            if (cpuDelta >= 0) {
                query.cpuSamples.increment();
                query.cpu.add(cpuDelta);
                (scope.repeated ? query.repeatCpu : query.firstCpu).add(cpuDelta);
            }
        }
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
        scope.probes++;
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
        long repetitions = QUERIES.values().stream().mapToLong(c -> c.repeats.sum()).sum();
        logger.info("BOOTOPTIM_RESOLUTION status=snapshot calls={} roots={} unique={} repeats={} hits={} failures={} sampled_roots={} cpu_samples={} sampled_cpu_ns={} sampled_wall_ns={} sampled_provider_cpu_ns={} inflight={} truncated={}",
                CALLS.sum(), ROOTS.sum(), QUERIES.size(), repetitions, HITS.sum(), FAILURES.sum(),
                SAMPLES.sum(), CPU_SAMPLES.sum(), ROOT_CPU.sum(), ROOT_WALL.sum(), CHILD_CPU.sum(), INFLIGHT.get(), truncated);
        PROVIDERS.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            Provider p = entry.getValue();
            logger.info("BOOTOPTIM_RESOLUTION_PROVIDER class={} calls={} hits={} failures={} samples={} cpu_samples={} sampled_cpu_ns={} sampled_wall_ns={}",
                    entry.getKey(), p.calls.sum(), p.hits.sum(), p.failures.sum(), p.samples.sum(),
                    p.cpuSamples.sum(), p.cpu.sum(), p.wall.sum());
        });
        FAMILIES.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            Query q = entry.getValue();
            logger.info("BOOTOPTIM_RESOLUTION_FAMILY family={} calls={} repeats={} hits={} probes={} samples={} cpu_samples={} sampled_cpu_ns={} sampled_wall_ns={} first_cpu_ns={} repeat_cpu_ns={}",
                    entry.getKey(), q.count.get(), q.repeats.sum(), q.hits.sum(), q.probes.sum(), q.samples.sum(),
                    q.cpuSamples.sum(), q.cpu.sum(), q.wall.sum(), q.firstCpu.sum(), q.repeatCpu.sum());
        });
        QUERIES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().count.get(), a.getValue().count.get())).limit(40).forEach(entry -> {
            Key k = entry.getKey(); Query q = entry.getValue();
            logger.info("BOOTOPTIM_RESOLUTION_QUERY manager={} id={} mode={} calls={} hits={} probes={} samples={} cpu_samples={} sampled_cpu_ns={}",
                    Integer.toHexString(System.identityHashCode(k.manager)), k.location, k.mode, q.count.get(), q.hits.sum(),
                    q.probes.sum(), q.samples.sum(), q.cpuSamples.sum(), q.cpu.sum());
        });
        CALLERS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(40).forEach(entry ->
                logger.info("BOOTOPTIM_RESOLUTION_CALLER trace={} samples={}", entry.getKey(), entry.getValue().sum()));
        // Counts retain manager identities only until this diagnostic snapshot.
        if (INFLIGHT.get() == 0) { QUERIES.clear(); PROVIDERS.clear(); FAMILIES.clear(); CALLERS.clear(); }
    }
}
