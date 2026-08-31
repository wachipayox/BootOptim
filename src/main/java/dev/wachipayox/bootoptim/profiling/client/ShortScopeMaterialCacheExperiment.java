package dev.wachipayox.bootoptim.profiling.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Diagnostic counters for the BlockGeometryBakingContext-scoped material cache experiment. */
public final class ShortScopeMaterialCacheExperiment {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ContextMaterialCache");

    private static volatile boolean active;
    private static long contexts;
    private static long materialCalls;
    private static long materialHits;
    private static long materialMisses;
    private static long maxEntries;

    private ShortScopeMaterialCacheExperiment() {}

    public static synchronized void beginExperiment() {
        contexts = materialCalls = materialHits = materialMisses = maxEntries = 0L;
        active = true;
    }

    public static void recordContext() {
        if (active) contexts++;
    }

    public static void recordHit(int entries) {
        if (!active) return;
        materialCalls++;
        materialHits++;
        if (entries > maxEntries) maxEntries = entries;
    }

    public static void recordMiss(int entries) {
        if (!active) return;
        materialCalls++;
        materialMisses++;
        if (entries > maxEntries) maxEntries = entries;
    }

    public static synchronized void finishExperiment() {
        if (!active) return;
        active = false;
        LOGGER.info(
                "BOOTOPTIM_CONTEXT_MATERIAL_CACHE status=experimental cache_scope=block_geometry_context contexts={} material_calls={} material_hits={} material_misses={} hit_percent={} max_entries_per_context={}",
                contexts,
                materialCalls,
                materialHits,
                materialMisses,
                percent(materialHits, materialCalls),
                maxEntries);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }
}
