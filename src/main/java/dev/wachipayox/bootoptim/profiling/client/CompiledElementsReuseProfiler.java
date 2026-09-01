package dev.wachipayox.bootoptim.profiling.client;

import java.util.IdentityHashMap;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Diagnostic identity accounting at addQuads-call granularity, never per face. */
public final class CompiledElementsReuseProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/CompiledElementsReuse");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.compiledElementsProfile");
    private static final IdentityHashMap<Object, Integer> CONTEXT_CALLS = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Integer> GEOMETRY_CALLS = new IdentityHashMap<>();

    private static boolean active;
    private static Thread ownerThread;
    private static long calls;
    private static long corrupt;

    private CompiledElementsReuseProfiler() {}

    public static void begin() {
        if (!ENABLED) return;
        CONTEXT_CALLS.clear();
        GEOMETRY_CALLS.clear();
        calls = corrupt = 0L;
        ownerThread = Thread.currentThread();
        active = true;
    }

    public static void observe(Object context, Object geometryList) {
        if (!active) return;
        if (Thread.currentThread() != ownerThread) {
            corrupt++;
            return;
        }
        calls++;
        CONTEXT_CALLS.merge(context, 1, Integer::sum);
        GEOMETRY_CALLS.merge(geometryList, 1, Integer::sum);
    }

    public static void finish() {
        if (!active) return;
        active = false;
        Stats contexts = stats(CONTEXT_CALLS);
        Stats geometries = stats(GEOMETRY_CALLS);
        LOGGER.info(
                "BOOTOPTIM_COMPILED_ELEMENTS_REUSE calls={} unique_contexts={} repeated_context_calls={} reused_contexts={} max_context_calls={} context_repeat_pct={} unique_geometry_lists={} repeated_geometry_calls={} reused_geometry_lists={} max_geometry_calls={} geometry_repeat_pct={} corrupt={}",
                calls,
                CONTEXT_CALLS.size(),
                contexts.repeatedCalls,
                contexts.reusedIdentities,
                contexts.maxCalls,
                pct(contexts.repeatedCalls, calls),
                GEOMETRY_CALLS.size(),
                geometries.repeatedCalls,
                geometries.reusedIdentities,
                geometries.maxCalls,
                pct(geometries.repeatedCalls, calls),
                corrupt);
        CONTEXT_CALLS.clear();
        GEOMETRY_CALLS.clear();
        ownerThread = null;
    }

    private static Stats stats(IdentityHashMap<Object, Integer> map) {
        long repeatedCalls = 0L;
        long reusedIdentities = 0L;
        int maxCalls = 0;
        for (int count : map.values()) {
            if (count > 1) {
                repeatedCalls += count - 1L;
                reusedIdentities++;
            }
            maxCalls = Math.max(maxCalls, count);
        }
        return new Stats(repeatedCalls, reusedIdentities, maxCalls);
    }

    private static String pct(long numerator, long denominator) {
        return denominator == 0L ? "0.000" : String.format(Locale.ROOT, "%.3f", numerator * 100.0D / denominator);
    }

    private record Stats(long repeatedCalls, long reusedIdentities, int maxCalls) {}
}
