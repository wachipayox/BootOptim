package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int TOP_CLASS_LIMIT = 20;

    private final ILaunchPluginService.ITransformerLoader delegate;
    private final long maxCacheBytes;
    private final int maxEntryBytes;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong cacheBytes = new AtomicLong();
    private final LongAdder calls = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder bypasses = new LongAdder();
    private final LongAdder hitBytes = new LongAdder();
    private final LongAdder missNanos = new LongAdder();
    private final AtomicLong maxMissNanos = new AtomicLong();
    private final LongAdder estimatedHitSavedNanos = new LongAdder();
    private final LongAdder racedMisses = new LongAdder();
    private final LongAdder raceDelegateNanos = new LongAdder();

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

        CacheEntry cached = cache.get(className);
        if (cached != null) {
            hits.increment();
            cached.hits.increment();
            hitBytes.add(cached.bytes.length);
            estimatedHitSavedNanos.add(cached.firstDelegateNanos);
            // Mixin only parses these bytes today, but a defensive copy keeps the cache immutable even if
            // a future consumer mutates the returned array.
            return cached.bytes.clone();
        }

        misses.increment();
        long started = System.nanoTime();
        byte[] transformed;
        long elapsedNanos;
        try {
            transformed = delegate.buildTransformedClassNodeFor(className);
        } finally {
            elapsedNanos = System.nanoTime() - started;
            missNanos.add(elapsedNanos);
            maxMissNanos.accumulateAndGet(elapsedNanos, Math::max);
        }

        if (transformed == null || transformed.length == 0 || transformed.length > maxEntryBytes) {
            bypasses.increment();
            return transformed;
        }

        if (reserve(transformed.length)) {
            byte[] snapshot = transformed.clone();
            CacheEntry candidate = new CacheEntry(snapshot, elapsedNanos);
            CacheEntry raced = cache.putIfAbsent(className, candidate);
            if (raced != null) {
                cacheBytes.addAndGet(-snapshot.length);
                racedMisses.increment();
                raceDelegateNanos.add(elapsedNanos);
                raced.racedMisses.increment();
                raced.raceDelegateNanos.add(elapsedNanos);
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
        long racedMissCount = racedMisses.sum();
        emit(String.format(Locale.ROOT,
                "summary=%s calls=%d hits=%d misses=%d hit_percent=%.2f entries=%d cached_mib=%.3f served_hit_mib=%.3f delegate_ms=%.3f avg_miss_us=%.3f max_miss_ms=%.3f bypasses=%d estimated_hit_saved_ms=%.3f raced_misses=%d race_delegate_ms=%.3f max_reuse_percent=%.2f",
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
                bypasses.sum(),
                estimatedHitSavedNanos.sum() / 1_000_000.0,
                racedMissCount,
                raceDelegateNanos.sum() / 1_000_000.0,
                callCount == 0 ? 0.0 : (hitCount + racedMissCount) * 100.0 / callCount));
        reportTopClasses();
    }

    private void reportTopClasses() {
        ArrayList<ClassDiagnostic> diagnostics = new ArrayList<>();
        cache.forEach((className, entry) -> {
            long hitCount = entry.hits.sum();
            long racedMissCount = entry.racedMisses.sum();
            if (hitCount != 0L || racedMissCount != 0L) {
                diagnostics.add(new ClassDiagnostic(
                        className,
                        entry.bytes.length,
                        entry.firstDelegateNanos,
                        hitCount,
                        racedMissCount,
                        entry.raceDelegateNanos.sum()));
            }
        });

        diagnostics.stream()
                .sorted(Comparator.comparingLong(ClassDiagnostic::estimatedSavedNanos)
                        .reversed()
                        .thenComparing(ClassDiagnostic::className))
                .limit(TOP_CLASS_LIMIT)
                .forEachOrdered(new RankedDiagnosticEmitter("top_saved")::emit);

        diagnostics.stream()
                .sorted(Comparator.comparingLong(ClassDiagnostic::hits)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(ClassDiagnostic::estimatedSavedNanos).reversed())
                        .thenComparing(ClassDiagnostic::className))
                .limit(TOP_CLASS_LIMIT)
                .forEachOrdered(new RankedDiagnosticEmitter("top_hits")::emit);
    }

    private final class RankedDiagnosticEmitter {
        private final String kind;
        private int rank;

        private RankedDiagnosticEmitter(String kind) {
            this.kind = kind;
        }

        private void emit(ClassDiagnostic diagnostic) {
            rank++;
            MixinSideLoadCache.emit(String.format(Locale.ROOT,
                    "%s rank=%d class=%s hits=%d first_delegate_ms=%.3f estimated_saved_ms=%.3f raced_misses=%d race_delegate_ms=%.3f bytes=%d",
                    kind,
                    rank,
                    diagnostic.className,
                    diagnostic.hits,
                    diagnostic.firstDelegateNanos / 1_000_000.0,
                    diagnostic.estimatedSavedNanos() / 1_000_000.0,
                    diagnostic.racedMisses,
                    diagnostic.raceDelegateNanos / 1_000_000.0,
                    diagnostic.bytes));
        }
    }

    private static final class CacheEntry {
        private final byte[] bytes;
        private final long firstDelegateNanos;
        private final LongAdder hits = new LongAdder();
        private final LongAdder racedMisses = new LongAdder();
        private final LongAdder raceDelegateNanos = new LongAdder();

        private CacheEntry(byte[] bytes, long firstDelegateNanos) {
            this.bytes = bytes;
            this.firstDelegateNanos = firstDelegateNanos;
        }
    }

    private record ClassDiagnostic(
            String className,
            int bytes,
            long firstDelegateNanos,
            long hits,
            long racedMisses,
            long raceDelegateNanos) {
        private long estimatedSavedNanos() {
            return saturatedMultiply(firstDelegateNanos, hits);
        }
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
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
