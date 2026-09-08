package dev.wachipayox.bootoptim.bootstrap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only aggregation for GAME classloader transformation cost.
 *
 * <p>The measured transform duration deliberately excludes this profiler's bookkeeping. That bookkeeping is
 * timed separately so real-pack results can quantify the observer overhead.</p>
 */
final class TransformClassProfiler {
    private static final int CHECKPOINT_INTERVAL = 5_000;
    private static final int HISTOGRAM_BUCKETS = 64;
    private static final int TOP_CLASSES = 40;
    private static final int TOP_GROUPS = 25;

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder TRANSFORM_NANOS = new LongAdder();
    private static final LongAdder PROFILER_NANOS = new LongAdder();
    private static final LongAdder NULL_CONTEXT_CALLS = new LongAdder();
    private static final LongAdder NON_NULL_CONTEXT_CALLS = new LongAdder();
    private static final LongAdder INPUT_BYTES = new LongAdder();
    private static final LongAdder OUTPUT_BYTES = new LongAdder();
    private static final LongAdder[] HISTOGRAM = new LongAdder[HISTOGRAM_BUCKETS];
    private static final Map<String, Stats> BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<String, Stats> BY_GROUP = new ConcurrentHashMap<>();
    private static final Map<String, Stats> BY_CONTEXT = new ConcurrentHashMap<>();
    private static final AtomicLong MAX_NANOS = new AtomicLong();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static volatile String maxClass = "<none>";

    static {
        for (int i = 0; i < HISTOGRAM.length; i++) {
            HISTOGRAM[i] = new LongAdder();
        }
    }

    private TransformClassProfiler() {
    }

    static void initialize() {
        if (SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> report("shutdown"),
                    "BootOptim Transform Profile Reporter"));
        }
        emit("BOOTOPTIM_TRANSFORM_PROFILE", "status=enabled mode=observe_only cache=false");
    }

    static void record(
            String className,
            String context,
            long transformNanos,
            int inputLength,
            int outputLength) {
        CALLS.increment();
        TRANSFORM_NANOS.add(transformNanos);
        INPUT_BYTES.add(inputLength);
        OUTPUT_BYTES.add(outputLength);
        if (context == null) {
            NULL_CONTEXT_CALLS.increment();
        } else {
            NON_NULL_CONTEXT_CALLS.increment();
        }

        HISTOGRAM[histogramBucket(transformNanos)].increment();
        BY_CLASS.computeIfAbsent(className, ignored -> new Stats()).add(transformNanos, inputLength, outputLength);
        BY_GROUP.computeIfAbsent(group(className), ignored -> new Stats()).add(transformNanos, inputLength, outputLength);
        BY_CONTEXT.computeIfAbsent(context == null ? "<null>" : context, ignored -> new Stats())
                .add(transformNanos, inputLength, outputLength);

        updateMax(transformNanos, className);

        long calls = CALLS.sum();
        if (calls % CHECKPOINT_INTERVAL == 0) {
            reportSummary("checkpoint");
        }
    }

    static void addBookkeepingNanos(long nanos) {
        PROFILER_NANOS.add(nanos);
    }

    private static void updateMax(long nanos, String className) {
        long previous = MAX_NANOS.get();
        while (nanos > previous) {
            if (MAX_NANOS.compareAndSet(previous, nanos)) {
                maxClass = className;
                return;
            }
            previous = MAX_NANOS.get();
        }
    }

    private static int histogramBucket(long nanos) {
        if (nanos <= 0L) {
            return 0;
        }
        int bucket = 64 - Long.numberOfLeadingZeros(nanos);
        return Math.min(bucket, HISTOGRAM_BUCKETS - 1);
    }

    private static String group(String className) {
        String[] parts = className.split("\\.");
        if (parts.length <= 3) {
            return className;
        }
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    static void report(String reason) {
        reportSummary(reason);
        long totalTransformNanos = TRANSFORM_NANOS.sum();
        reportTop("class", BY_CLASS, TOP_CLASSES, totalTransformNanos);
        reportTop("group", BY_GROUP, TOP_GROUPS, totalTransformNanos);
        reportTop("context", BY_CONTEXT, 10, totalTransformNanos);
    }

    private static void reportSummary(String reason) {
        long calls = CALLS.sum();
        long transformNanos = TRANSFORM_NANOS.sum();
        long profilerNanos = PROFILER_NANOS.sum();
        String payload = String.format(
                Locale.ROOT,
                "summary=%s calls=%d transform_ms=%.3f profiler_overhead_ms=%.3f null_context_calls=%d nonnull_context_calls=%d input_mib=%.3f output_mib=%.3f p50_us=%.3f p90_us=%.3f p95_us=%.3f p99_us=%.3f max_us=%.3f max_class=%s",
                reason,
                calls,
                transformNanos / 1_000_000.0,
                profilerNanos / 1_000_000.0,
                NULL_CONTEXT_CALLS.sum(),
                NON_NULL_CONTEXT_CALLS.sum(),
                INPUT_BYTES.sum() / (1024.0 * 1024.0),
                OUTPUT_BYTES.sum() / (1024.0 * 1024.0),
                percentileMicros(calls, 0.50),
                percentileMicros(calls, 0.90),
                percentileMicros(calls, 0.95),
                percentileMicros(calls, 0.99),
                MAX_NANOS.get() / 1_000.0,
                maxClass);
        emit("BOOTOPTIM_TRANSFORM_PROFILE", payload);
    }

    private static double percentileMicros(long totalCalls, double percentile) {
        if (totalCalls <= 0L) {
            return 0.0;
        }
        long target = Math.max(1L, (long) Math.ceil(totalCalls * percentile));
        long cumulative = 0L;
        for (int i = 0; i < HISTOGRAM.length; i++) {
            cumulative += HISTOGRAM[i].sum();
            if (cumulative >= target) {
                long upperBoundNanos = i >= 63 ? Long.MAX_VALUE : (1L << i);
                return upperBoundNanos / 1_000.0;
            }
        }
        return 0.0;
    }

    private static void reportTop(String dimension, Map<String, Stats> source, int limit, long totalTransformNanos) {
        List<Map.Entry<String, Stats>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Stats> entry) -> entry.getValue().totalNanos())
                .reversed());

        int emitted = Math.min(limit, entries.size());
        for (int i = 0; i < emitted; i++) {
            Map.Entry<String, Stats> entry = entries.get(i);
            Stats stats = entry.getValue();
            long calls = stats.calls();
            long nanos = stats.totalNanos();
            double share = totalTransformNanos == 0L ? 0.0 : nanos * 100.0 / totalTransformNanos;
            double avgMicros = calls == 0L ? 0.0 : nanos / 1_000.0 / calls;
            String payload = String.format(
                    Locale.ROOT,
                    "dimension=%s rank=%d key=%s calls=%d total_ms=%.3f share_percent=%.2f avg_us=%.3f max_us=%.3f input_kib=%.3f output_kib=%.3f",
                    dimension,
                    i + 1,
                    entry.getKey(),
                    calls,
                    nanos / 1_000_000.0,
                    share,
                    avgMicros,
                    stats.maxNanos() / 1_000.0,
                    stats.inputBytes() / 1024.0,
                    stats.outputBytes() / 1024.0);
            emit("BOOTOPTIM_TRANSFORM_PROFILE_TOP", payload);
        }
    }

    private static void emit(String category, String payload) {
        System.out.println(category + " " + payload);
        StartupDiagnostics.event(category, payload);
    }

    private static final class Stats {
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAdder inputBytes = new LongAdder();
        private final LongAdder outputBytes = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void add(long nanos, int inputLength, int outputLength) {
            calls.increment();
            totalNanos.add(nanos);
            inputBytes.add(inputLength);
            outputBytes.add(outputLength);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        long calls() {
            return calls.sum();
        }

        long totalNanos() {
            return totalNanos.sum();
        }

        long inputBytes() {
            return inputBytes.sum();
        }

        long outputBytes() {
            return outputBytes.sum();
        }

        long maxNanos() {
            return maxNanos.get();
        }
    }
}
