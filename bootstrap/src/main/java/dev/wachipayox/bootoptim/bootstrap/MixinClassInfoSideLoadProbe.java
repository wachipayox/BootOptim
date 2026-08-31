package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Observe-only probe for Mixin's ModLauncher ITransformerLoader side-loads.
 *
 * <p>This specifically quantifies the ceiling of the broken negative ClassInfo cache present in
 * Fabric Mixin 0.15.2+mixin.0.8.7. A later Fabric fix (2c6ea183) changed ClassInfo.forName from
 * {@code cache.get(name) == null} to {@code !cache.containsKey(name)}, so unresolved classes can be
 * cached as null. This probe does not emulate that fix: every request still reaches ModLauncher.</p>
 */
final class MixinClassInfoSideLoadProbe implements ILaunchPluginService.ITransformerLoader {
    private static final int TOP_LIMIT = 30;

    private final ILaunchPluginService.ITransformerLoader delegate;
    private final ClassLoader mixinClassLoader;
    private final Map<String, ClassStats> byClass = new ConcurrentHashMap<>();
    private final LongAdder calls = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder classNotFound = new LongAdder();
    private final LongAdder repeatedCalls = new LongAdder();
    private final LongAdder repeatedClassNotFound = new LongAdder();
    private final LongAdder delegateNanos = new LongAdder();
    private final LongAdder classNotFoundNanos = new LongAdder();
    private final LongAdder repeatedClassNotFoundNanos = new LongAdder();

    MixinClassInfoSideLoadProbe(
            ILaunchPluginService.ITransformerLoader delegate,
            ClassLoader mixinClassLoader) {
        this.delegate = delegate;
        this.mixinClassLoader = mixinClassLoader;
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> report("shutdown"),
                "BootOptim Mixin ClassInfo Probe Reporter"));
        emit("status=enabled mode=observe_only cache=false");
    }

    @Override
    public byte[] buildTransformedClassNodeFor(String className) throws ClassNotFoundException {
        calls.increment();
        ClassStats stats = byClass.computeIfAbsent(className, ignored -> new ClassStats());
        long priorCalls = stats.calls.getAndIncrement();
        if (priorCalls > 0L) {
            repeatedCalls.increment();
        }

        long started = System.nanoTime();
        boolean missing = false;
        boolean repeatedMissing = false;
        try {
            byte[] result = delegate.buildTransformedClassNodeFor(className);
            successes.increment();
            stats.successes.increment();
            return result;
        } catch (ClassNotFoundException ex) {
            missing = true;
            classNotFound.increment();
            long priorFailures = stats.classNotFound.getAndIncrement();
            if (priorFailures > 0L) {
                repeatedMissing = true;
                repeatedClassNotFound.increment();
            }
            throw ex;
        } finally {
            long elapsed = System.nanoTime() - started;
            delegateNanos.add(elapsed);
            stats.delegateNanos.add(elapsed);
            stats.firstDelegateNanos.compareAndSet(-1L, elapsed);
            if (missing) {
                classNotFoundNanos.add(elapsed);
                stats.classNotFoundNanos.add(elapsed);
                if (repeatedMissing) {
                    repeatedClassNotFoundNanos.add(elapsed);
                    stats.repeatedClassNotFoundNanos.add(elapsed);
                }
            }
        }
    }

    private void report(String reason) {
        long totalCalls = calls.sum();
        long missingCalls = classNotFound.sum();
        long repeatedMissingCalls = repeatedClassNotFound.sum();
        emit(String.format(
                Locale.ROOT,
                "summary=%s calls=%d unique_classes=%d successes=%d class_not_found=%d repeated_calls=%d repeated_class_not_found=%d repeated_class_not_found_percent=%.2f delegate_ms=%.3f class_not_found_ms=%.3f repeated_class_not_found_ms=%.3f",
                reason,
                totalCalls,
                byClass.size(),
                successes.sum(),
                missingCalls,
                repeatedCalls.sum(),
                repeatedMissingCalls,
                missingCalls == 0L ? 0.0 : repeatedMissingCalls * 100.0 / missingCalls,
                delegateNanos.sum() / 1_000_000.0,
                classNotFoundNanos.sum() / 1_000_000.0,
                repeatedClassNotFoundNanos.sum() / 1_000_000.0));

        reportNullClassInfoCorrelation();

        ArrayList<Map.Entry<String, ClassStats>> repeatedFailures = new ArrayList<>(byClass.entrySet());
        repeatedFailures.removeIf(entry -> entry.getValue().classNotFound.get() < 2L);
        repeatedFailures.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().repeatedClassNotFoundNanos.sum())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("repeated_missing", repeatedFailures);

        ArrayList<Map.Entry<String, ClassStats>> expensive = new ArrayList<>(byClass.entrySet());
        expensive.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().delegateNanos.sum())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("delegate_time", expensive);
    }

    private void reportNullClassInfoCorrelation() {
        Set<String> nullEntries = MixinClassInfoCacheSnapshot.nullEntries(mixinClassLoader);
        long matchedClasses = 0L;
        long matchedCalls = 0L;
        long matchedRepeatedCalls = 0L;
        long matchedTotalNanos = 0L;
        long matchedRepeatedNanos = 0L;
        ArrayList<Map.Entry<String, ClassStats>> matched = new ArrayList<>();

        for (String internalName : nullEntries) {
            String binaryName = internalName.replace('/', '.');
            ClassStats stats = byClass.get(binaryName);
            if (stats == null) {
                stats = byClass.get(internalName);
            }
            if (stats == null) {
                continue;
            }

            matchedClasses++;
            long classCalls = stats.calls.get();
            long total = stats.delegateNanos.sum();
            long first = Math.max(0L, stats.firstDelegateNanos.get());
            matchedCalls += classCalls;
            matchedRepeatedCalls += Math.max(0L, classCalls - 1L);
            matchedTotalNanos += total;
            matchedRepeatedNanos += Math.max(0L, total - first);
            matched.add(Map.entry(binaryName, stats));
        }

        emit(String.format(
                Locale.ROOT,
                "classinfo_cache=null_entries null_entries=%d matched_sideload_classes=%d matched_sideload_calls=%d matched_repeated_calls=%d matched_delegate_ms=%.3f matched_est_repeated_ms=%.3f",
                nullEntries.size(),
                matchedClasses,
                matchedCalls,
                matchedRepeatedCalls,
                matchedTotalNanos / 1_000_000.0,
                matchedRepeatedNanos / 1_000_000.0));

        matched.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> estimatedRepeatedNanos(entry.getValue()))
                .reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("classinfo_null_entry", matched);
    }

    private static long estimatedRepeatedNanos(ClassStats stats) {
        long first = Math.max(0L, stats.firstDelegateNanos.get());
        return Math.max(0L, stats.delegateNanos.sum() - first);
    }

    private void emitTop(String dimension, ArrayList<Map.Entry<String, ClassStats>> entries) {
        int count = Math.min(TOP_LIMIT, entries.size());
        for (int index = 0; index < count; index++) {
            Map.Entry<String, ClassStats> entry = entries.get(index);
            ClassStats stats = entry.getValue();
            emit(String.format(
                    Locale.ROOT,
                    "dimension=%s rank=%d class=%s calls=%d successes=%d class_not_found=%d repeated_class_not_found=%d delegate_ms=%.3f est_repeated_ms=%.3f class_not_found_ms=%.3f repeated_class_not_found_ms=%.3f",
                    dimension,
                    index + 1,
                    entry.getKey(),
                    stats.calls.get(),
                    stats.successes.sum(),
                    stats.classNotFound.get(),
                    Math.max(0L, stats.classNotFound.get() - 1L),
                    stats.delegateNanos.sum() / 1_000_000.0,
                    estimatedRepeatedNanos(stats) / 1_000_000.0,
                    stats.classNotFoundNanos.sum() / 1_000_000.0,
                    stats.repeatedClassNotFoundNanos.sum() / 1_000_000.0));
        }
    }

    long repeatedClassNotFoundForTests() {
        return repeatedClassNotFound.sum();
    }

    long repeatedClassNotFoundNanosForTests() {
        return repeatedClassNotFoundNanos.sum();
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MIXIN_CLASSINFO_PROBE " + payload);
        StartupDiagnostics.event("BOOTOPTIM_MIXIN_CLASSINFO_PROBE", payload);
    }

    private static final class ClassStats {
        private final AtomicLong calls = new AtomicLong();
        private final LongAdder successes = new LongAdder();
        private final AtomicLong classNotFound = new AtomicLong();
        private final AtomicLong firstDelegateNanos = new AtomicLong(-1L);
        private final LongAdder delegateNanos = new LongAdder();
        private final LongAdder classNotFoundNanos = new LongAdder();
        private final LongAdder repeatedClassNotFoundNanos = new LongAdder();
    }
}
