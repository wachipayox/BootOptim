package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Reload-scoped cache for MoreCulling 1.0.8 NativeImage translucency scans.
 *
 * <p>The cache is intentionally narrow: it is active only while a resource reload is in flight,
 * only for calls whose layered-image argument is null, and keys by NativeImage object identity plus
 * the exact integer bounds received by MoreCulling. It is cleared at reload start and again when the
 * reload completion future finishes, so image references and results never survive a reload boundary.</p>
 */
public final class MoreCullingTranslucencyCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENABLE_PROPERTY = "boot_optim.moreCullingTranslucencyCache";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    private static final int MAX_ENTRIES = 4096;
    private static final Object LOCK = new Object();
    private static final BoundedIdentityBoundsBooleanCache CACHE =
            new BoundedIdentityBoundsBooleanCache(MAX_ENTRIES);

    private static long generation;
    private static boolean active;
    private static boolean failedOpen;
    private static long hits;
    private static long misses;
    private static long stores;
    private static long saturated;
    private static long layeredBypasses;

    private MoreCullingTranslucencyCache() {
    }

    public enum Lookup {
        MISS,
        FALSE,
        TRUE
    }

    public static long beginReload() {
        if (!ENABLED) {
            return -1L;
        }
        synchronized (LOCK) {
            generation++;
            active = true;
            failedOpen = false;
            CACHE.clear();
            hits = 0L;
            misses = 0L;
            stores = 0L;
            saturated = 0L;
            layeredBypasses = 0L;
            return generation;
        }
    }

    public static long currentGeneration() {
        if (!ENABLED) {
            return -1L;
        }
        synchronized (LOCK) {
            return active ? generation : -1L;
        }
    }

    public static void endReload(long token, Throwable reloadFailure) {
        if (!ENABLED || token < 0L) {
            return;
        }
        synchronized (LOCK) {
            if (!active || generation != token) {
                return;
            }
            LOGGER.info(
                    "BOOTOPTIM_MORECULLING_CACHE generation={} hits={} misses={} stores={} saturated={} layered_bypass={} entries={} failed_open={} reload_failure={}",
                    generation,
                    hits,
                    misses,
                    stores,
                    saturated,
                    layeredBypasses,
                    CACHE.size(),
                    failedOpen,
                    reloadFailure == null ? "none" : reloadFailure.getClass().getName());
            CACHE.clear();
            active = false;
        }
    }

    public static Lookup lookup(
            Object image,
            Object layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight) {
        if (!ENABLED || image == null) {
            return Lookup.MISS;
        }
        synchronized (LOCK) {
            if (!active || failedOpen) {
                return Lookup.MISS;
            }
            if (layeredImages != null) {
                layeredBypasses++;
                return Lookup.MISS;
            }
            try {
                int value = CACHE.get(image, minWidth, maxWidth, minHeight, maxHeight);
                if (value == BoundedIdentityBoundsBooleanCache.MISS) {
                    misses++;
                    return Lookup.MISS;
                }
                hits++;
                return value == BoundedIdentityBoundsBooleanCache.TRUE ? Lookup.TRUE : Lookup.FALSE;
            } catch (RuntimeException unexpected) {
                failOpenLocked("lookup", unexpected);
                return Lookup.MISS;
            }
        }
    }

    public static void store(
            Object image,
            Object layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight,
            boolean value) {
        if (!ENABLED || image == null || layeredImages != null) {
            return;
        }
        synchronized (LOCK) {
            if (!active || failedOpen) {
                return;
            }
            try {
                int result = CACHE.putIfAbsent(image, minWidth, maxWidth, minHeight, maxHeight, value);
                if (result == BoundedIdentityBoundsBooleanCache.STORED) {
                    stores++;
                } else if (result == BoundedIdentityBoundsBooleanCache.FULL) {
                    saturated++;
                }
            } catch (RuntimeException unexpected) {
                failOpenLocked("store", unexpected);
            }
        }
    }

    private static void failOpenLocked(String phase, RuntimeException unexpected) {
        if (!failedOpen) {
            LOGGER.warn(
                    "MoreCulling translucency cache failed open during {}; stock translucency scans will continue for this reload",
                    phase,
                    unexpected);
        }
        failedOpen = true;
        CACHE.clear();
    }
}
