package dev.wachipayox.bootoptim.profiling.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Diagnostic counters for the selective BlockGeometryBakingContext material cache experiment. */
public final class ShortScopeMaterialCacheExperiment {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ContextMaterialCache");

    // bakeModels begins, executes and finishes this experiment on the same resource-reload worker. Keeping this plain
    // avoids adding a volatile read to each of the millions of diagnostic hot-path calls.
    private static boolean active;
    private static long cachedContexts;
    private static long materialCalls;
    private static long directLocalCalls;
    private static long complexCalls;
    private static long complexHits;
    private static long complexMisses;
    private static long maxEntries;

    private ShortScopeMaterialCacheExperiment() {}

    public static synchronized void beginExperiment() {
        cachedContexts = materialCalls = directLocalCalls = complexCalls = complexHits = complexMisses = maxEntries = 0L;
        active = true;
    }

    public static void recordCachedContext() {
        if (active) cachedContexts++;
    }

    public static void recordDirectLocal() {
        if (!active) return;
        materialCalls++;
        directLocalCalls++;
    }

    public static void recordComplexHit(int entries) {
        if (!active) return;
        materialCalls++;
        complexCalls++;
        complexHits++;
        if (entries > maxEntries) maxEntries = entries;
    }

    public static void recordComplexMiss(int entries) {
        if (!active) return;
        materialCalls++;
        complexCalls++;
        complexMisses++;
        if (entries > maxEntries) maxEntries = entries;
    }

    public static synchronized void finishExperiment() {
        if (!active) return;
        active = false;
        LOGGER.info(
                "BOOTOPTIM_CONTEXT_MATERIAL_CACHE status=experimental mode=selective_complex_only cached_contexts={} material_calls={} direct_local_calls={} direct_local_percent={} complex_calls={} complex_hits={} complex_misses={} complex_hit_percent={} max_entries_per_context={}",
                cachedContexts,
                materialCalls,
                directLocalCalls,
                percent(directLocalCalls, materialCalls),
                complexCalls,
                complexHits,
                complexMisses,
                percent(complexHits, complexCalls),
                maxEntries);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }
}
