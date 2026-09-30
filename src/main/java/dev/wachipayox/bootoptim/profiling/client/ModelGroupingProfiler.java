package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.function.Supplier;

/** Diagnostic only: attribute current-call grouping without caching or skipping callbacks. */
public final class ModelGroupingProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileModelGrouping");
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

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
            Object result = original.get();
            success = true;
            return result;
        } finally {
            if (!success) scope.failures++;
            if (outer) scope.wallNs += System.nanoTime() - start;
            scope.depth--;
        }
    }

    private static final class Scope {
        long calls;
        long nested;
        long failures;
        long wallNs;
        int depth;
    }
}
