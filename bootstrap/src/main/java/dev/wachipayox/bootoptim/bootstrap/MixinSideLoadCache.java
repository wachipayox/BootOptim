package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, process-local memoization for Mixin's ITransformerLoader side-load requests.
 *
 * <p>The first request for every class always goes through ModLauncher's stock transformer path. Only
 * later requests for the same canonical class name in the same JVM reuse the transformed bytes. The
 * cache is intentionally not persisted, so it cannot become stale across pack/config/version changes.</p>
 */
final class MixinSideLoadCache implements ILaunchPluginService.ITransformerLoader {
    private static final long DEFAULT_MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int DEFAULT_MAX_ENTRY_BYTES = 4 * 1024 * 1024;

    private final ILaunchPluginService.ITransformerLoader delegate;
    private final long maxCacheBytes;
    private final int maxEntryBytes;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();
    private final AtomicLong cacheBytes = new AtomicLong();
    private final LongAdder calls = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder bypasses = new LongAdder();
    private final LongAdder hitBytes = new LongAdder();
    private final LongAdder missNanos = new LongAdder();
    private final AtomicLong maxMissNanos = new AtomicLong();

    MixinSideLoadCache(ILaunchPluginService.ITransformerLoader delegate) {
        this.delegate = delegate;
        this.maxCacheBytes = positiveLongProperty(
                "boot_optim.mixinSideLoadCache.maxBytes", DEFAULT_MAX_CACHE_BYTES);
        this.maxEntryBytes = (int) Math.min(Integer.MAX_VALUE, positiveLongProperty(
                "boot_optim.mixinSideLoadCache.maxEntryBytes", DEFAULT_MAX_ENTRY_BYTES));

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> report("shutdown"), "BootOptim Mixin SideLoad Cache Reporter"));
        emit(String.format(Locale.ROOT,
                "status=enabled max_cache_mib=%.1f max_entry_mib=%.1f",
                maxCacheBytes / 1048576.0,
                maxEntryBytes / 1048576.0));
    }

    @Override
    public byte[] buildTransformedClassNodeFor(String className) throws ClassNotFoundException {
        calls.increment();

        byte[] cached = cache.get(className);
        if (cached != null) {
            hits.increment();
            hitBytes.add(cached.length);
            // Mixin only parses these bytes today, but a defensive copy keeps the cache immutable even if
            // a future consumer mutates the returned array.
            return cached.clone();
        }

        misses.increment();
        long started = System.nanoTime();
        byte[] transformed;
        try {
            transformed = delegate.buildTransformedClassNodeFor(className);
        } finally {
            long elapsed = System.nanoTime() - started;
            missNanos.add(elapsed);
            maxMissNanos.accumulateAndGet(elapsed, Math::max);
        }

        if (transformed == null || transformed.length == 0 || transformed.length > maxEntryBytes) {
            bypasses.increment();
            return transformed;
        }

        if (reserve(transformed.length)) {
            byte[] snapshot = transformed.clone();
            byte[] raced = cache.putIfAbsent(className, snapshot);
            if (raced != null) {
                cacheBytes.addAndGet(-snapshot.length);
            }
        } else {
            bypasses.increment();
        }

        return transformed;
    }

    private boolean reserve(int bytes) {
        while (true) {
            long current = cacheBytes.get();
            long next = current + bytes;
            if (next > maxCacheBytes) {
                return false;
            }
            if (cacheBytes.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    private void report(String reason) {
        long callCount = calls.sum();
        long hitCount = hits.sum();
        long missCount = misses.sum();
        emit(String.format(Locale.ROOT,
                "summary=%s calls=%d hits=%d misses=%d hit_percent=%.2f entries=%d cached_mib=%.3f served_hit_mib=%.3f delegate_ms=%.3f avg_miss_us=%.3f max_miss_ms=%.3f bypasses=%d",
                reason,
                callCount,
                hitCount,
                missCount,
                callCount == 0 ? 0.0 : hitCount * 100.0 / callCount,
                cache.size(),
                cacheBytes.get() / 1048576.0,
                hitBytes.sum() / 1048576.0,
                missNanos.sum() / 1_000_000.0,
                missCount == 0 ? 0.0 : missNanos.sum() / 1_000.0 / missCount,
                maxMissNanos.get() / 1_000_000.0,
                bypasses.sum()));
    }

    private static long positiveLongProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MIXIN_SIDELOAD_CACHE " + payload);
        StartupDiagnostics.event("BOOTOPTIM_MIXIN_SIDELOAD_CACHE", payload);
    }
}
