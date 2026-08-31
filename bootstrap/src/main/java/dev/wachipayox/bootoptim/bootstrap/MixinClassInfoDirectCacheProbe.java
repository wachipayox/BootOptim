package dev.wachipayox.bootoptim.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Direct, observe-only instrumentation for the private {@code ClassInfo.cache} map.
 *
 * <p>Fabric Mixin 0.15.2+mixin.0.8.7 checks {@code cache.get(name) == null} in
 * {@code ClassInfo.forName}. Because failed resolutions are deliberately stored as null, a negative
 * cache entry is treated as a miss and the full resolution path runs again. This map preserves
 * normal {@link HashMap} behaviour and records that exact sequence without changing any returned
 * value or suppressing any retry.</p>
 */
final class MixinClassInfoDirectCacheProbe extends HashMap<String, Object> {
    private static final long serialVersionUID = 1L;
    private static final String CLASS_INFO = "org.spongepowered.asm.mixin.transformer.ClassInfo";
    private static final int TOP_LIMIT = 20;
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private final ConcurrentHashMap<String, ClassStats> byClass = new ConcurrentHashMap<>();
    private final Set<String> negativeKeys = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<ArrayDeque<RetryAttempt>> activeRetries = ThreadLocal.withInitial(ArrayDeque::new);

    private final LongAdder forNameCalls = new LongAdder();
    private final LongAdder positiveHits = new LongAdder();
    private final LongAdder firstAbsentResolutions = new LongAdder();
    private final LongAdder negativeCachedGets = new LongAdder();
    private final LongAdder negativeRetries = new LongAdder();
    private final LongAdder negativeRetryRawNanos = new LongAdder();
    private final LongAdder negativeRetryNanos = new LongAdder();
    private final LongAdder negativeStillNegativeRetries = new LongAdder();
    private final LongAdder negativeStillNegativeNanos = new LongAdder();
    private final LongAdder negativeRecoveries = new LongAdder();
    private final LongAdder negativeRecoveryNanos = new LongAdder();
    private final LongAdder classificationProbeNanos = new LongAdder();

    @SuppressWarnings({"rawtypes", "unchecked"})
    MixinClassInfoDirectCacheProbe(Map<?, ?> original) {
        super((Map) original);
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> report("shutdown"),
                "BootOptim Mixin ClassInfo Direct Probe Reporter"));
        emit(String.format(Locale.ROOT, "status=enabled mode=direct_cache_map initial_entries=%d", size()));
    }

    @Override
    public Object get(Object key) {
        long classifyStarted = System.nanoTime();
        String caller = classInfoCaller();
        long classifyNanos = System.nanoTime() - classifyStarted;
        classificationProbeNanos.add(classifyNanos);
        chargeActiveRetries(classifyNanos);

        Object value = super.get(key);
        if (!"forName".equals(caller) || !(key instanceof String className)) {
            return value;
        }

        forNameCalls.increment();
        ClassStats stats = byClass.computeIfAbsent(className, ignored -> new ClassStats());
        stats.forNameCalls.increment();

        if (value != null) {
            positiveHits.increment();
            stats.positiveHits.increment();
            return value;
        }

        if (!super.containsKey(className)) {
            firstAbsentResolutions.increment();
            stats.firstAbsentResolutions.increment();
            return null;
        }

        // This is the exact bug condition in 0.15.2+mixin.0.8.7:
        // containsKey(name) == true, but cache.get(name) == null. ClassInfo.forName will now
        // execute the expensive resolution path again instead of returning the cached negative.
        negativeCachedGets.increment();
        negativeRetries.increment();
        stats.negativeRetries.increment();
        activeRetries.get().addLast(new RetryAttempt(className, System.nanoTime()));
        return null;
    }

    @Override
    public Object put(String key, Object value) {
        finishRetryIfPresent(key, value);

        if (value == null) {
            negativeKeys.add(key);
            byClass.computeIfAbsent(key, ignored -> new ClassStats()).becameNegative = true;
        }

        return super.put(key, value);
    }

    private void finishRetryIfPresent(String key, Object resolvedValue) {
        ArrayDeque<RetryAttempt> attempts = activeRetries.get();
        if (attempts.isEmpty()) {
            return;
        }

        RetryAttempt matched = null;
        Iterator<RetryAttempt> descending = attempts.descendingIterator();
        while (descending.hasNext()) {
            RetryAttempt attempt = descending.next();
            if (attempt.className.equals(key)) {
                matched = attempt;
                descending.remove();
                break;
            }
        }
        if (matched == null) {
            return;
        }

        long raw = System.nanoTime() - matched.startedNanos;
        long adjusted = Math.max(0L, raw - matched.probeOverheadNanos);
        negativeRetryRawNanos.add(raw);
        negativeRetryNanos.add(adjusted);

        ClassStats stats = byClass.computeIfAbsent(key, ignored -> new ClassStats());
        stats.negativeRetryRawNanos.add(raw);
        stats.negativeRetryNanos.add(adjusted);

        if (resolvedValue == null) {
            negativeStillNegativeRetries.increment();
            negativeStillNegativeNanos.add(adjusted);
            stats.stillNegativeRetries.increment();
            stats.stillNegativeNanos.add(adjusted);
        } else {
            // Compatibility guard: the stock broken cache can recover if a previously missing class
            // becomes resolvable later. A sticky negative-cache backport would change that behaviour.
            negativeRecoveries.increment();
            negativeRecoveryNanos.add(adjusted);
            stats.recoveries.increment();
            stats.recoveryNanos.add(adjusted);
        }

        if (attempts.isEmpty()) {
            activeRetries.remove();
        }
    }

    private void chargeActiveRetries(long nanos) {
        ArrayDeque<RetryAttempt> attempts = activeRetries.get();
        if (attempts.isEmpty()) {
            return;
        }
        for (RetryAttempt attempt : attempts) {
            attempt.probeOverheadNanos += nanos;
        }
    }

    /**
     * Returns the nearest ClassInfo frame so an outer forName does not cause a nested fromClassNode
     * cache access to be misclassified as another forName call.
     */
    private static String classInfoCaller() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> CLASS_INFO.equals(frame.getClassName()))
                .findFirst()
                .map(StackWalker.StackFrame::getMethodName)
                .orElse(""));
    }

    private void report(String reason) {
        long retries = negativeRetries.sum();
        emit(String.format(
                Locale.ROOT,
                "summary=%s for_name_calls=%d positive_cache_hits=%d first_absent_resolutions=%d negative_cached_gets=%d negative_unique=%d negative_retries=%d negative_retry_ms=%.3f negative_retry_raw_ms=%.3f negative_still_negative_retries=%d negative_still_negative_retry_ms=%.3f negative_recoveries=%d negative_recovery_ms=%.3f classification_probe_ms=%.3f",
                reason,
                forNameCalls.sum(),
                positiveHits.sum(),
                firstAbsentResolutions.sum(),
                negativeCachedGets.sum(),
                negativeKeys.size(),
                retries,
                negativeRetryNanos.sum() / 1_000_000.0,
                negativeRetryRawNanos.sum() / 1_000_000.0,
                negativeStillNegativeRetries.sum(),
                negativeStillNegativeNanos.sum() / 1_000_000.0,
                negativeRecoveries.sum(),
                negativeRecoveryNanos.sum() / 1_000_000.0,
                classificationProbeNanos.sum() / 1_000_000.0));

        ArrayList<Map.Entry<String, ClassStats>> retried = new ArrayList<>(byClass.entrySet());
        retried.removeIf(entry -> entry.getValue().negativeRetries.sum() == 0L);

        retried.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().negativeRetries.sum())
                .reversed()
                .thenComparing(Comparator.comparingLong(
                                (Map.Entry<String, ClassStats> entry) -> entry.getValue().negativeRetryNanos.sum())
                        .reversed())
                .thenComparing(Map.Entry::getKey));
        emitTop("retry_count", retried);

        retried.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().negativeRetryNanos.sum())
                .reversed()
                .thenComparing(Comparator.comparingLong(
                                (Map.Entry<String, ClassStats> entry) -> entry.getValue().negativeRetries.sum())
                        .reversed())
                .thenComparing(Map.Entry::getKey));
        emitTop("retry_time", retried);

        ArrayList<String> negative = new ArrayList<>(new HashSet<>(negativeKeys));
        negative.sort(String::compareTo);
        emit(String.format(Locale.ROOT, "negative_keys=%d", negative.size()));
    }

    private void emitTop(String dimension, ArrayList<Map.Entry<String, ClassStats>> entries) {
        int count = Math.min(TOP_LIMIT, entries.size());
        for (int index = 0; index < count; index++) {
            Map.Entry<String, ClassStats> entry = entries.get(index);
            ClassStats stats = entry.getValue();
            emit(String.format(
                    Locale.ROOT,
                    "dimension=%s rank=%d class=%s retries=%d retry_ms=%.3f retry_raw_ms=%.3f still_negative_retries=%d still_negative_ms=%.3f recoveries=%d recovery_ms=%.3f for_name_calls=%d positive_hits=%d first_absent=%d negative=%s",
                    dimension,
                    index + 1,
                    entry.getKey(),
                    stats.negativeRetries.sum(),
                    stats.negativeRetryNanos.sum() / 1_000_000.0,
                    stats.negativeRetryRawNanos.sum() / 1_000_000.0,
                    stats.stillNegativeRetries.sum(),
                    stats.stillNegativeNanos.sum() / 1_000_000.0,
                    stats.recoveries.sum(),
                    stats.recoveryNanos.sum() / 1_000_000.0,
                    stats.forNameCalls.sum(),
                    stats.positiveHits.sum(),
                    stats.firstAbsentResolutions.sum(),
                    stats.becameNegative));
        }
    }

    long negativeRetriesForTests() {
        return negativeRetries.sum();
    }

    long negativeUniqueForTests() {
        return negativeKeys.size();
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MIXIN_CLASSINFO_DIRECT " + payload);
        StartupDiagnostics.event("BOOTOPTIM_MIXIN_CLASSINFO_DIRECT", payload);
    }

    private static final class RetryAttempt {
        private final String className;
        private final long startedNanos;
        private long probeOverheadNanos;

        private RetryAttempt(String className, long startedNanos) {
            this.className = className;
            this.startedNanos = startedNanos;
        }
    }

    private static final class ClassStats {
        private final LongAdder forNameCalls = new LongAdder();
        private final LongAdder positiveHits = new LongAdder();
        private final LongAdder firstAbsentResolutions = new LongAdder();
        private final LongAdder negativeRetries = new LongAdder();
        private final LongAdder negativeRetryRawNanos = new LongAdder();
        private final LongAdder negativeRetryNanos = new LongAdder();
        private final LongAdder stillNegativeRetries = new LongAdder();
        private final LongAdder stillNegativeNanos = new LongAdder();
        private final LongAdder recoveries = new LongAdder();
        private final LongAdder recoveryNanos = new LongAdder();
        private volatile boolean becameNegative;
    }
}
