package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.function.Supplier;

/** Diagnostic only: attribute current-call grouping without caching or skipping callbacks. */
public final class ModelGroupingProfiler {
    private static final boolean WORK = Boolean.getBoolean("boot_optim.profileBlockStateWork");
    private static final boolean ENABLED = WORK || Boolean.getBoolean("boot_optim.profileModelGrouping");
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    public enum Phase { LOCATION, PARSE, DISCOVERY, GROUP, PUBLICATION, FINALIZATION }

    private ModelGroupingProfiler() {}

    public static void loadAll(Runnable original) {
        if (!ENABLED) {
            original.run();
            return;
        }
        Scope previous = CURRENT.get();
        Scope scope = new Scope();
        CURRENT.set(scope);
        long start = System.nanoTime();
        boolean success = false;
        try {
            original.run();
            success = true;
        } finally {
            long total = System.nanoTime() - start;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            LogUtils.getLogger().info(
                    "BOOTOPTIM_MODEL_GROUPING success={} available={} load_all_wall_ms={} group_and_coloring_wall_ms={} outside_groups_wall_ms={} group_calls={} nested_calls={} failed_group_calls={} thread={}",
                    success, scope.calls > 0, total / 1e6, scope.wallNs / 1e6,
                    (total - scope.wallNs) / 1e6, scope.calls, scope.nested, scope.failures,
                    Thread.currentThread().getName());
            if (WORK) {
                boolean available = scope.phaseCalls[Phase.LOCATION.ordinal()] > 0
                        && scope.phaseCalls[Phase.PARSE.ordinal()] > 0
                        && scope.phaseCalls[Phase.DISCOVERY.ordinal()] > 0
                        && scope.phaseCalls[Phase.PUBLICATION.ordinal()] > 0
                        && scope.phaseCalls[Phase.FINALIZATION.ordinal()] > 0 && scope.calls > 0;
                LogUtils.getLogger().info(
                        "BOOTOPTIM_BLOCKSTATE_WORK success={} available={} load_all_wall_ms={} locations_ms={} parse_ms={} discovery_ms={} group_factory_ms={} publication_ms={} finalization_ms={} remainder_ms={} location_calls={} parse_calls={} discovery_calls={} publication_calls={} finalization_calls={} group_calls={}",
                        success, available, total / 1e6, scope.phaseNs[0] / 1e6,
                        scope.phaseNs[1] / 1e6, scope.phaseNs[2] / 1e6, scope.phaseNs[3] / 1e6,
                        scope.phaseNs[4] / 1e6, scope.phaseNs[5] / 1e6, (total - scope.measuredNs) / 1e6,
                        scope.phaseCalls[0], scope.phaseCalls[1], scope.phaseCalls[2],
                        scope.phaseCalls[4], scope.phaseCalls[5], scope.calls);
            }
        }
    }

    public static Object group(Supplier<Object> original) {
        Scope scope = ENABLED ? CURRENT.get() : null;
        if (scope == null) return original.get();
        scope.calls++;
        boolean outer = scope.depth++ == 0;
        if (!outer) scope.nested++;
        long start = outer ? System.nanoTime() : 0;
        boolean success = false;
        try {
            Object result = phase(Phase.GROUP, original);
            success = true;
            return result;
        } finally {
            if (!success) scope.failures++;
            if (outer) scope.wallNs += System.nanoTime() - start;
            scope.depth--;
        }
    }

    /** Exclusive timing: nested measured calls are subtracted from the enclosing bucket. */
    public static <T> T phase(Phase phase, Supplier<T> original) {
        Scope scope = WORK ? CURRENT.get() : null;
        if (scope == null) return original.get();
        int bucket = phase.ordinal();
        scope.phaseCalls[bucket]++;
        long childrenBefore = scope.measuredNs;
        long start = System.nanoTime();
        try {
            return original.get();
        } finally {
            long exclusive = System.nanoTime() - start - (scope.measuredNs - childrenBefore);
            scope.phaseNs[bucket] += exclusive;
            scope.measuredNs += exclusive;
        }
    }

    private static final class Scope {
        long calls;
        long nested;
        long failures;
        long wallNs;
        int depth;
        long measuredNs;
        final long[] phaseNs = new long[Phase.values().length];
        final long[] phaseCalls = new long[Phase.values().length];
    }
}
