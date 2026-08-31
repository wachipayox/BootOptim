package dev.wachipayox.bootoptim.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only aggregation for GAME classloader transformation cost.
 *
 * <p>Transforms can recursively request more transformed classes (Mixin does this heavily). The original profiler
 * measured inclusive time, which is useful but double-counts nested work. This version records both inclusive and
 * exclusive time by subtracting completed child transform calls on the same thread. Profiler bookkeeping is tracked
 * separately and included in the child-wall subtraction because a parent transform necessarily waits for it too.</p>
 */
final class TransformClassProfiler {
    private static final int CHECKPOINT_INTERVAL = 5_000;
    private static final int HISTOGRAM_BUCKETS = 64;
    private static final int TOP_CLASSES = 40;
    private static final int TOP_GROUPS = 25;

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder ROOT_CALLS = new LongAdder();
    private static final LongAdder NESTED_CALLS = new LongAdder();
    private static final LongAdder INCLUSIVE_NANOS = new LongAdder();
    private static final LongAdder EXCLUSIVE_NANOS = new LongAdder();
    private static final LongAdder ROOT_NANOS = new LongAdder();
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
    private static final AtomicInteger MAX_DEPTH = new AtomicInteger();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<ArrayDeque<CallFrame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
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
        emit("BOOTOPTIM_TRANSFORM_PROFILE", "status=enabled mode=observe_only cache=false nesting=true");
    }

    static CallFrame begin(String className, String context) {
        ArrayDeque<CallFrame> stack = STACK.get();
        CallFrame frame = new CallFrame(className, context, stack.size());
        stack.push(frame);
        MAX_DEPTH.accumulateAndGet(frame.depth, Math::max);
        return frame;
    }

    static int currentDepth() {
        int size = STACK.get().size();
        return Math.max(0, size - 1);
    }

    static void record(
            CallFrame frame,
            long transformNanos,
            int inputLength,
            int outputLength) {
        ArrayDeque<CallFrame> stack = STACK.get();
        if (stack.peek() == frame) {
            stack.pop();
        } else {
            // Diagnostic state must never be allowed to break startup. Recover best-effort if a future loader path
            // completes out of the expected LIFO order.
            stack.remove(frame);
        }

        frame.transformNanos = transformNanos;
        long exclusiveNanos = Math.max(0L, transformNanos - frame.childWallNanos);

        CALLS.increment();
        INCLUSIVE_NANOS.add(transformNanos);
        EXCLUSIVE_NANOS.add(exclusiveNanos);
        if (frame.depth == 0) {
            ROOT_CALLS.increment();
            ROOT_NANOS.add(transformNanos);
        } else {
            NESTED_CALLS.increment();
        }
        INPUT_BYTES.add(inputLength);
        OUTPUT_BYTES.add(outputLength);
        if (frame.context == null) {
            NULL_CONTEXT_CALLS.increment();
        } else {
            NON_NULL_CONTEXT_CALLS.increment();
        }

        HISTOGRAM[histogramBucket(transformNanos)].increment();
        BY_CLASS.computeIfAbsent(frame.className, ignored -> new Stats())
                .add(transformNanos, exclusiveNanos, inputLength, outputLength, frame.depth);
        BY_GROUP.computeIfAbsent(group(frame.className), ignored -> new Stats())
                .add(transformNanos, exclusiveNanos, inputLength, outputLength, frame.depth);
        BY_CONTEXT.computeIfAbsent(frame.context == null ? "<null>" : frame.context, ignored -> new Stats())
                .add(transformNanos, exclusiveNanos, inputLength, outputLength, frame.depth);

        updateMax(transformNanos, frame.className);

        long calls = CALLS.sum();
        if (calls % CHECKPOINT_INTERVAL == 0) {
            reportSummary("checkpoint");
        }
    }

    static void finishBookkeeping(CallFrame frame, long profilerNanos) {
        PROFILER_NANOS.add(profilerNanos);
        // The parent's stock super.maybeTransformClassBytes timer includes the entire child method invocation,
        // including this diagnostic bookkeeping. Subtract both so parent exclusive time remains meaningful.
        CallFrame parent = STACK.get().peek();
        if (parent != null) {
            parent.childWallNanos += frame.transformNanos + profilerNanos;
        }
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
        long totalInclusive = INCLUSIVE_NANOS.sum();
        long totalExclusive = EXCLUSIVE_NANOS.sum();
        reportTop("class_inclusive", BY_CLASS, TOP_CLASSES, totalInclusive, false);
        reportTop("class_exclusive", BY_CLASS, TOP_CLASSES, totalExclusive, true);
        reportTop("group_inclusive", BY_GROUP, TOP_GROUPS, totalInclusive, false);
        reportTop("group_exclusive", BY_GROUP, TOP_GROUPS, totalExclusive, true);
        reportTop("context_inclusive", BY_CONTEXT, 10, totalInclusive, false);
        reportTop("context_exclusive", BY_CONTEXT, 10, totalExclusive, true);
        LaunchPluginProfiler.report(reason);
    }

    private static void reportSummary(String reason) {
        long calls = CALLS.sum();
        String payload = String.format(
                Locale.ROOT,
                "summary=%s calls=%d root_calls=%d nested_calls=%d max_depth=%d inclusive_transform_ms=%.3f exclusive_transform_ms=%.3f root_transform_ms=%.3f profiler_overhead_ms=%.3f null_context_calls=%d nonnull_context_calls=%d input_mib=%.3f output_mib=%.3f p50_us=%.3f p90_us=%.3f p95_us=%.3f p99_us=%.3f max_us=%.3f max_class=%s",
                reason,
                calls,
                ROOT_CALLS.sum(),
                NESTED_CALLS.sum(),
                MAX_DEPTH.get(),
                INCLUSIVE_NANOS.sum() / 1_000_000.0,
                EXCLUSIVE_NANOS.sum() / 1_000_000.0,
                ROOT_NANOS.sum() / 1_000_000.0,
                PROFILER_NANOS.sum() / 1_000_000.0,
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

    private static void reportTop(
            String dimension,
            Map<String, Stats> source,
            int limit,
            long denominatorNanos,
            boolean sortExclusive) {
        List<Map.Entry<String, Stats>> entries = new ArrayList<>(source.entrySet());
        Comparator<Map.Entry<String, Stats>> comparator = Comparator.comparingLong(entry ->
                sortExclusive ? entry.getValue().exclusiveNanos() : entry.getValue().inclusiveNanos());
        entries.sort(comparator.reversed());

        int emitted = Math.min(limit, entries.size());
        for (int i = 0; i < emitted; i++) {
            Map.Entry<String, Stats> entry = entries.get(i);
            Stats stats = entry.getValue();
            long calls = stats.calls();
            long rankedNanos = sortExclusive ? stats.exclusiveNanos() : stats.inclusiveNanos();
            double share = denominatorNanos == 0L ? 0.0 : rankedNanos * 100.0 / denominatorNanos;
            String payload = String.format(
                    Locale.ROOT,
                    "dimension=%s rank=%d key=%s calls=%d root_calls=%d nested_calls=%d inclusive_ms=%.3f exclusive_ms=%.3f share_percent=%.2f avg_inclusive_us=%.3f max_us=%.3f input_kib=%.3f output_kib=%.3f",
                    dimension,
                    i + 1,
                    entry.getKey(),
                    calls,
                    stats.rootCalls(),
                    stats.nestedCalls(),
                    stats.inclusiveNanos() / 1_000_000.0,
                    stats.exclusiveNanos() / 1_000_000.0,
                    share,
                    calls == 0L ? 0.0 : stats.inclusiveNanos() / 1_000.0 / calls,
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

    static final class CallFrame {
        final String className;
        final String context;
        final int depth;
        long childWallNanos;
        long transformNanos;

        private CallFrame(String className, String context, int depth) {
            this.className = className;
            this.context = context;
            this.depth = depth;
        }
    }

    private static final class Stats {
        private final LongAdder calls = new LongAdder();
        private final LongAdder rootCalls = new LongAdder();
        private final LongAdder nestedCalls = new LongAdder();
        private final LongAdder inclusiveNanos = new LongAdder();
        private final LongAdder exclusiveNanos = new LongAdder();
        private final LongAdder inputBytes = new LongAdder();
        private final LongAdder outputBytes = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void add(long inclusive, long exclusive, int inputLength, int outputLength, int depth) {
            calls.increment();
            if (depth == 0) {
                rootCalls.increment();
            } else {
                nestedCalls.increment();
            }
            inclusiveNanos.add(inclusive);
            exclusiveNanos.add(exclusive);
            inputBytes.add(inputLength);
            outputBytes.add(outputLength);
            maxNanos.accumulateAndGet(inclusive, Math::max);
        }

        long calls() {
            return calls.sum();
        }

        long rootCalls() {
            return rootCalls.sum();
        }

        long nestedCalls() {
            return nestedCalls.sum();
        }

        long inclusiveNanos() {
            return inclusiveNanos.sum();
        }

        long exclusiveNanos() {
            return exclusiveNanos.sum();
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
