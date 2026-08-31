package dev.wachipayox.bootoptim.tailagent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Runtime hooks injected into Mixin and ModLauncher by the diagnostic javaagent. */
public final class TailRuntime {
    private static final int TOP_LIMIT = 20;
    private static final int COMPUTE_MAXS = 1;
    private static final int COMPUTE_FRAMES = 2;
    private static final int SIMPLE_REWRITE = 0x100;

    private static final ThreadLocal<Map<Key, Boolean>> MIXIN_RESULTS = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<ArrayDeque<Key>> MIXIN_CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
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

    private TailRuntime() {
    }

    /**
     * Captures Mixin's target identity at method entry, while all original argument locals are
     * verifier-live. Some return stack-map frames in Mixin 0.8.7 deliberately mark the now-dead
     * {@code reason} argument as TOP, so return hooks must not reload it.
     */
    public static void beginMixinProcess(String className, String reason) {
        MIXIN_CONTEXTS.get().addLast(new Key(className, reason));
    }

    /** Called immediately before MixinTransformationHandler's original boolean return. */
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

    /** Called immediately before the original ClassNode.accept(ClassWriter). */
    public static void beginAccept(String className, int flags, int inputBytes, String reason) {
        boolean selected = Boolean.TRUE.equals(MIXIN_RESULTS.get().get(new Key(className, reason)));
        int activeDepth = SELECTED_ACTIVE_DEPTH.get();
        boolean aggregate = selected && activeDepth == 0;
        if (selected) {
            SELECTED_ACTIVE_DEPTH.set(activeDepth + 1);
            if (!aggregate) {
                NESTED_SELECTED_SKIPPED.increment();
            }
        }
        ACCEPT_STACK.get().addLast(new TimingFrame(
                className, flags, inputBytes, reason, selected, aggregate, System.nanoTime()));
    }

    /** Called immediately after the original ClassNode.accept(ClassWriter). */
    public static void endAccept() {
        ArrayDeque<TimingFrame> stack = ACCEPT_STACK.get();
        TimingFrame frame = stack.removeLast();
        long elapsed = System.nanoTime() - frame.startedNanos;
        if (frame.selected) {
            SELECTED_ACTIVE_DEPTH.set(Math.max(0, SELECTED_ACTIVE_DEPTH.get() - 1));
        }
        if (frame.aggregate) {
            ACCEPT_CALLS.increment();
            ACCEPT_NANOS.add(elapsed);
            INPUT_BYTES.add(frame.inputBytes);
            MAX_ACCEPT_NANOS.accumulateAndGet(elapsed, Math::max);

            ClassStats stats = BY_CLASS.computeIfAbsent(frame.className, ignored -> new ClassStats());
            stats.acceptCalls.increment();
            stats.acceptNanos.add(elapsed);
            stats.inputBytes.add(frame.inputBytes);
            stats.lastFlags = frame.flags;

            FlagStats flags = BY_FLAGS.computeIfAbsent(frame.flags, ignored -> new FlagStats());
            flags.acceptCalls.increment();
            flags.acceptNanos.add(elapsed);
            flags.inputBytes.add(frame.inputBytes);
        }
        if (stack.isEmpty()) {
            ACCEPT_STACK.remove();
        }
    }

    /** Called immediately before the original ClassWriter.toByteArray(). */
    public static void beginToByteArray(String className, int flags, int inputBytes, String reason) {
        boolean selected = Boolean.TRUE.equals(MIXIN_RESULTS.get().get(new Key(className, reason)));
        int activeDepth = SELECTED_ACTIVE_DEPTH.get();
        boolean aggregate = selected && activeDepth == 0;
        if (selected) {
            SELECTED_ACTIVE_DEPTH.set(activeDepth + 1);
            if (!aggregate) {
                NESTED_SELECTED_SKIPPED.increment();
            }
        }
        BYTES_STACK.get().addLast(new TimingFrame(
                className, flags, inputBytes, reason, selected, aggregate, System.nanoTime()));
    }

    /** Called immediately after the original ClassWriter.toByteArray(), with the produced array length. */
    public static void endToByteArray(int outputBytes) {
        ArrayDeque<TimingFrame> stack = BYTES_STACK.get();
        TimingFrame frame = stack.removeLast();
        long elapsed = System.nanoTime() - frame.startedNanos;
        if (frame.selected) {
            SELECTED_ACTIVE_DEPTH.set(Math.max(0, SELECTED_ACTIVE_DEPTH.get() - 1));
        }
        if (frame.aggregate) {
            TO_BYTES_CALLS.increment();
            TO_BYTES_NANOS.add(elapsed);
            OUTPUT_BYTES.add(outputBytes);
            MAX_TO_BYTES_NANOS.accumulateAndGet(elapsed, Math::max);

            ClassStats stats = BY_CLASS.computeIfAbsent(frame.className, ignored -> new ClassStats());
            stats.toBytesCalls.increment();
            stats.toBytesNanos.add(elapsed);
            stats.outputBytes.add(outputBytes);
            stats.lastOutputBytes = outputBytes;
            stats.lastFlags = frame.flags;

            FlagStats flags = BY_FLAGS.computeIfAbsent(frame.flags, ignored -> new FlagStats());
            flags.toBytesCalls.increment();
            flags.toBytesNanos.add(elapsed);
            flags.outputBytes.add(outputBytes);
        }
        if (stack.isEmpty()) {
            BYTES_STACK.remove();
        }
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

    private record TimingFrame(
            String className,
            int flags,
            int inputBytes,
            String reason,
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
