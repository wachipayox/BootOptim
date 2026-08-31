package org.spongepowered.asm.service.modlauncher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic runtime defined by the javaagent directly into Mixin's module/classloader.
 *
 * <p>This class deliberately depends only on JDK types so both Mixin and ModLauncher can call it
 * without resolving any class from the javaagent's own loader during transformation.</p>
 */
public final class BootOptimTailRuntime {
    private static final int TOP_LIMIT = 20;
    private static final int COMPUTE_MAXS = 1;
    private static final int COMPUTE_FRAMES = 2;
    private static final int SIMPLE_REWRITE = 0x100;

    private static final ThreadLocal<Map<Key, Boolean>> MIXIN_RESULTS = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<ArrayDeque<Key>> MIXIN_CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<TransformContext>> TRANSFORM_CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<TimingFrame>> ACCEPT_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<TimingFrame>> BYTES_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> SELECTED_ACTIVE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final ConcurrentHashMap<String, ClassStats> BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, FlagStats> BY_FLAGS = new ConcurrentHashMap<>();
    private static final LongAdder MIXIN_PROCESS_CALLS = new LongAdder();
    private static final LongAdder MIXIN_REWRITES = new LongAdder();
    private static final LongAdder ACCEPT_CALLS = new LongAdder();
    private static final LongAdder TO_BYTES_CALLS = new LongAdder();
    private static final LongAdder ACCEPT_NANOS = new LongAdder();
    private static final LongAdder TO_BYTES_NANOS = new LongAdder();
    private static final LongAdder INPUT_BYTES = new LongAdder();
    private static final LongAdder OUTPUT_BYTES = new LongAdder();
    private static final LongAdder NESTED_SELECTED_SKIPPED = new LongAdder();
    private static final AtomicLong MAX_ACCEPT_NANOS = new AtomicLong();
    private static final AtomicLong MAX_TO_BYTES_NANOS = new AtomicLong();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> report("shutdown"),
                "BootOptim ModLauncher Tail Reporter"));
    }

    private BootOptimTailRuntime() {
    }

    public static void beginMixinProcess(String className, String reason) {
        MIXIN_CONTEXTS.get().addLast(new Key(className, reason));
    }

    public static boolean recordMixinResult(boolean transformed) {
        ArrayDeque<Key> contexts = MIXIN_CONTEXTS.get();
        Key key = contexts.removeLast();
        if (contexts.isEmpty()) {
            MIXIN_CONTEXTS.remove();
        }

        MIXIN_PROCESS_CALLS.increment();
        if (transformed) {
            MIXIN_REWRITES.increment();
        }
        MIXIN_RESULTS.get().put(key, transformed);
        return transformed;
    }

    public static void beginClassTransform(String className, int inputBytes, String reason) {
        TRANSFORM_CONTEXTS.get().addLast(new TransformContext(className, reason, inputBytes));
    }

    public static void setClassTransformFlags(int flags) {
        currentTransform().flags = flags;
    }

    public static void endClassTransform() {
        ArrayDeque<TransformContext> contexts = TRANSFORM_CONTEXTS.get();
        contexts.removeLast();
        if (contexts.isEmpty()) {
            TRANSFORM_CONTEXTS.remove();
        }
    }

    public static void beginAccept() {
        TransformContext context = currentTransform();
        boolean selected = Boolean.TRUE.equals(MIXIN_RESULTS.get().get(context.key()));
        int activeDepth = SELECTED_ACTIVE_DEPTH.get();
        boolean aggregate = selected && activeDepth == 0;
        if (selected) {
            SELECTED_ACTIVE_DEPTH.set(activeDepth + 1);
            if (!aggregate) {
                NESTED_SELECTED_SKIPPED.increment();
            }
        }
        ACCEPT_STACK.get().addLast(new TimingFrame(context, selected, aggregate, System.nanoTime()));
    }

    public static void endAccept() {
        ArrayDeque<TimingFrame> stack = ACCEPT_STACK.get();
        TimingFrame frame = stack.removeLast();
        long elapsed = System.nanoTime() - frame.startedNanos;
        if (frame.selected) {
            SELECTED_ACTIVE_DEPTH.set(Math.max(0, SELECTED_ACTIVE_DEPTH.get() - 1));
        }
        if (frame.aggregate) {
            TransformContext context = frame.context;
            ACCEPT_CALLS.increment();
            ACCEPT_NANOS.add(elapsed);
            INPUT_BYTES.add(context.inputBytes);
            MAX_ACCEPT_NANOS.accumulateAndGet(elapsed, Math::max);

            ClassStats stats = BY_CLASS.computeIfAbsent(context.className, ignored -> new ClassStats());
            stats.acceptCalls.increment();
            stats.acceptNanos.add(elapsed);
            stats.inputBytes.add(context.inputBytes);
            stats.lastFlags = context.flags;

            FlagStats flags = BY_FLAGS.computeIfAbsent(context.flags, ignored -> new FlagStats());
            flags.acceptCalls.increment();
            flags.acceptNanos.add(elapsed);
            flags.inputBytes.add(context.inputBytes);
        }
        if (stack.isEmpty()) {
            ACCEPT_STACK.remove();
        }
    }

    public static void beginToByteArray() {
        TransformContext context = currentTransform();
        boolean selected = Boolean.TRUE.equals(MIXIN_RESULTS.get().get(context.key()));
        int activeDepth = SELECTED_ACTIVE_DEPTH.get();
        boolean aggregate = selected && activeDepth == 0;
        if (selected) {
            SELECTED_ACTIVE_DEPTH.set(activeDepth + 1);
            if (!aggregate) {
                NESTED_SELECTED_SKIPPED.increment();
            }
        }
        BYTES_STACK.get().addLast(new TimingFrame(context, selected, aggregate, System.nanoTime()));
    }

    public static void endToByteArray(int outputBytes) {
        ArrayDeque<TimingFrame> stack = BYTES_STACK.get();
        TimingFrame frame = stack.removeLast();
        long elapsed = System.nanoTime() - frame.startedNanos;
        if (frame.selected) {
            SELECTED_ACTIVE_DEPTH.set(Math.max(0, SELECTED_ACTIVE_DEPTH.get() - 1));
        }
        if (frame.aggregate) {
            TransformContext context = frame.context;
            TO_BYTES_CALLS.increment();
            TO_BYTES_NANOS.add(elapsed);
            OUTPUT_BYTES.add(outputBytes);
            MAX_TO_BYTES_NANOS.accumulateAndGet(elapsed, Math::max);

            ClassStats stats = BY_CLASS.computeIfAbsent(context.className, ignored -> new ClassStats());
            stats.toBytesCalls.increment();
            stats.toBytesNanos.add(elapsed);
            stats.outputBytes.add(outputBytes);
            stats.lastOutputBytes = outputBytes;
            stats.lastFlags = context.flags;

            FlagStats flags = BY_FLAGS.computeIfAbsent(context.flags, ignored -> new FlagStats());
            flags.toBytesCalls.increment();
            flags.toBytesNanos.add(elapsed);
            flags.outputBytes.add(outputBytes);
        }
        if (stack.isEmpty()) {
            BYTES_STACK.remove();
        }
    }

    private static TransformContext currentTransform() {
        TransformContext context = TRANSFORM_CONTEXTS.get().peekLast();
        if (context == null) {
            throw new IllegalStateException("No active ClassTransformer context");
        }
        return context;
    }

    public static void report(String reason) {
        emit(String.format(
                Locale.ROOT,
                "summary=%s mixin_process_calls=%d mixin_rewrites=%d classes=%d accept_calls=%d accept_ms=%.3f to_bytes_calls=%d to_bytes_ms=%.3f tail_total_ms=%.3f input_mib=%.3f output_mib=%.3f max_accept_ms=%.3f max_to_bytes_ms=%.3f nested_selected_skipped=%d",
                reason,
                MIXIN_PROCESS_CALLS.sum(),
                MIXIN_REWRITES.sum(),
                BY_CLASS.size(),
                ACCEPT_CALLS.sum(),
                ACCEPT_NANOS.sum() / 1_000_000.0,
                TO_BYTES_CALLS.sum(),
                TO_BYTES_NANOS.sum() / 1_000_000.0,
                (ACCEPT_NANOS.sum() + TO_BYTES_NANOS.sum()) / 1_000_000.0,
                INPUT_BYTES.sum() / 1048576.0,
                OUTPUT_BYTES.sum() / 1048576.0,
                MAX_ACCEPT_NANOS.get() / 1_000_000.0,
                MAX_TO_BYTES_NANOS.get() / 1_000_000.0,
                NESTED_SELECTED_SKIPPED.sum()));

        ArrayList<Map.Entry<Integer, FlagStats>> flags = new ArrayList<>(BY_FLAGS.entrySet());
        flags.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, FlagStats> entry : flags) {
            FlagStats stats = entry.getValue();
            int value = entry.getKey();
            emit(String.format(
                    Locale.ROOT,
                    "flags=%d compute_frames=%s compute_maxs=%s simple_rewrite=%s accept_calls=%d accept_ms=%.3f to_bytes_calls=%d to_bytes_ms=%.3f total_ms=%.3f input_mib=%.3f output_mib=%.3f",
                    value,
                    (value & COMPUTE_FRAMES) != 0,
                    (value & COMPUTE_MAXS) != 0,
                    (value & SIMPLE_REWRITE) != 0,
                    stats.acceptCalls.sum(),
                    stats.acceptNanos.sum() / 1_000_000.0,
                    stats.toBytesCalls.sum(),
                    stats.toBytesNanos.sum() / 1_000_000.0,
                    (stats.acceptNanos.sum() + stats.toBytesNanos.sum()) / 1_000_000.0,
                    stats.inputBytes.sum() / 1048576.0,
                    stats.outputBytes.sum() / 1048576.0));
        }

        ArrayList<Map.Entry<String, ClassStats>> classes = new ArrayList<>(BY_CLASS.entrySet());
        classes.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().totalNanos())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("total", classes);

        classes.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().acceptNanos.sum())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("accept", classes);

        classes.sort(Comparator
                .comparingLong((Map.Entry<String, ClassStats> entry) -> entry.getValue().toBytesNanos.sum())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("to_bytes", classes);
    }

    private static void emitTop(String dimension, ArrayList<Map.Entry<String, ClassStats>> classes) {
        int count = Math.min(TOP_LIMIT, classes.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<String, ClassStats> entry = classes.get(i);
            ClassStats stats = entry.getValue();
            emit(String.format(
                    Locale.ROOT,
                    "dimension=%s rank=%d class=%s flags=%d compute_frames=%s compute_maxs=%s simple_rewrite=%s accept_calls=%d accept_ms=%.3f to_bytes_calls=%d to_bytes_ms=%.3f total_ms=%.3f input_bytes=%d output_bytes_last=%d output_bytes_sum=%d",
                    dimension,
                    i + 1,
                    entry.getKey(),
                    stats.lastFlags,
                    (stats.lastFlags & COMPUTE_FRAMES) != 0,
                    (stats.lastFlags & COMPUTE_MAXS) != 0,
                    (stats.lastFlags & SIMPLE_REWRITE) != 0,
                    stats.acceptCalls.sum(),
                    stats.acceptNanos.sum() / 1_000_000.0,
                    stats.toBytesCalls.sum(),
                    stats.toBytesNanos.sum() / 1_000_000.0,
                    stats.totalNanos() / 1_000_000.0,
                    stats.inputBytes.sum(),
                    stats.lastOutputBytes,
                    stats.outputBytes.sum()));
        }
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MODLAUNCHER_TAIL " + payload);
    }

    private record Key(String className, String reason) {
    }

    private static final class TransformContext {
        private final String className;
        private final String reason;
        private final int inputBytes;
        private int flags;

        private TransformContext(String className, String reason, int inputBytes) {
            this.className = className;
            this.reason = reason;
            this.inputBytes = inputBytes;
        }

        private Key key() {
            return new Key(className, reason);
        }
    }

    private record TimingFrame(
            TransformContext context,
            boolean selected,
            boolean aggregate,
            long startedNanos) {
    }

    private static final class ClassStats {
        private final LongAdder acceptCalls = new LongAdder();
        private final LongAdder toBytesCalls = new LongAdder();
        private final LongAdder acceptNanos = new LongAdder();
        private final LongAdder toBytesNanos = new LongAdder();
        private final LongAdder inputBytes = new LongAdder();
        private final LongAdder outputBytes = new LongAdder();
        private volatile int lastFlags;
        private volatile int lastOutputBytes;

        private long totalNanos() {
            return acceptNanos.sum() + toBytesNanos.sum();
        }
    }

    private static final class FlagStats {
        private final LongAdder acceptCalls = new LongAdder();
        private final LongAdder toBytesCalls = new LongAdder();
        private final LongAdder acceptNanos = new LongAdder();
        private final LongAdder toBytesNanos = new LongAdder();
        private final LongAdder inputBytes = new LongAdder();
        private final LongAdder outputBytes = new LongAdder();
    }
}
