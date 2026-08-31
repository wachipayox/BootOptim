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

/** Single-class JDK-only runtime injected into Mixin's module by the diagnostic javaagent. */
public final class BootOptimTailRuntime {
    private static final int TOP_LIMIT = 20;
    private static final int COMPUTE_MAXS = 1;
    private static final int COMPUTE_FRAMES = 2;
    private static final int SIMPLE_REWRITE = 0x100;

    private static final int CS_ACCEPT_CALLS = 0;
    private static final int CS_BYTES_CALLS = 1;
    private static final int CS_ACCEPT_NANOS = 2;
    private static final int CS_BYTES_NANOS = 3;
    private static final int CS_INPUT_BYTES = 4;
    private static final int CS_OUTPUT_BYTES = 5;
    private static final int CS_LAST_FLAGS = 6;
    private static final int CS_LAST_OUTPUT = 7;
    private static final int CLASS_STAT_COUNT = 8;

    private static final int FS_ACCEPT_CALLS = 0;
    private static final int FS_BYTES_CALLS = 1;
    private static final int FS_ACCEPT_NANOS = 2;
    private static final int FS_BYTES_NANOS = 3;
    private static final int FS_INPUT_BYTES = 4;
    private static final int FS_OUTPUT_BYTES = 5;
    private static final int FLAG_STAT_COUNT = 6;

    // A string key avoids helper-owned record/class files: className + NUL + reason.
    private static final ThreadLocal<Map<String, Boolean>> MIXIN_RESULTS = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<ArrayDeque<String>> MIXIN_CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
    // Transform context layout: className(String), reason(String), inputBytes(Integer), flags(Integer).
    private static final ThreadLocal<ArrayDeque<Object[]>> TRANSFORM_CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
    // Timing frame layout: context(Object[]), selected(Boolean), aggregate(Boolean), startedNanos(Long).
    private static final ThreadLocal<ArrayDeque<Object[]>> ACCEPT_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Object[]>> BYTES_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> SELECTED_ACTIVE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final ConcurrentHashMap<String, AtomicLong[]> BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicLong[]> BY_FLAGS = new ConcurrentHashMap<>();
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
        MIXIN_CONTEXTS.get().addLast(key(className, reason));
    }

    public static boolean recordMixinResult(boolean transformed) {
        ArrayDeque<String> contexts = MIXIN_CONTEXTS.get();
        String key = contexts.removeLast();
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
        TRANSFORM_CONTEXTS.get().addLast(new Object[] {className, reason, inputBytes, 0});
    }

    public static void setClassTransformFlags(int flags) {
        currentTransform()[3] = flags;
    }

    public static void endClassTransform() {
        ArrayDeque<Object[]> contexts = TRANSFORM_CONTEXTS.get();
        contexts.removeLast();
        if (contexts.isEmpty()) {
            TRANSFORM_CONTEXTS.remove();
        }
    }

    public static void beginAccept() {
        beginTiming(ACCEPT_STACK);
    }

    public static void endAccept() {
        Object[] frame = endTiming(ACCEPT_STACK);
        if (!((Boolean) frame[2])) {
            return;
        }
        Object[] context = (Object[]) frame[0];
        long elapsed = (Long) frame[3];
        String className = (String) context[0];
        int inputBytes = (Integer) context[2];
        int flags = (Integer) context[3];

        ACCEPT_CALLS.increment();
        ACCEPT_NANOS.add(elapsed);
        INPUT_BYTES.add(inputBytes);
        MAX_ACCEPT_NANOS.accumulateAndGet(elapsed, Math::max);

        AtomicLong[] classStats = BY_CLASS.computeIfAbsent(className, ignored -> atomics(CLASS_STAT_COUNT));
        classStats[CS_ACCEPT_CALLS].incrementAndGet();
        classStats[CS_ACCEPT_NANOS].addAndGet(elapsed);
        classStats[CS_INPUT_BYTES].addAndGet(inputBytes);
        classStats[CS_LAST_FLAGS].set(flags);

        AtomicLong[] flagStats = BY_FLAGS.computeIfAbsent(flags, ignored -> atomics(FLAG_STAT_COUNT));
        flagStats[FS_ACCEPT_CALLS].incrementAndGet();
        flagStats[FS_ACCEPT_NANOS].addAndGet(elapsed);
        flagStats[FS_INPUT_BYTES].addAndGet(inputBytes);
    }

    public static void beginToByteArray() {
        beginTiming(BYTES_STACK);
    }

    public static void endToByteArray(int outputBytes) {
        Object[] frame = endTiming(BYTES_STACK);
        if (!((Boolean) frame[2])) {
            return;
        }
        Object[] context = (Object[]) frame[0];
        long elapsed = (Long) frame[3];
        String className = (String) context[0];
        int flags = (Integer) context[3];

        TO_BYTES_CALLS.increment();
        TO_BYTES_NANOS.add(elapsed);
        OUTPUT_BYTES.add(outputBytes);
        MAX_TO_BYTES_NANOS.accumulateAndGet(elapsed, Math::max);

        AtomicLong[] classStats = BY_CLASS.computeIfAbsent(className, ignored -> atomics(CLASS_STAT_COUNT));
        classStats[CS_BYTES_CALLS].incrementAndGet();
        classStats[CS_BYTES_NANOS].addAndGet(elapsed);
        classStats[CS_OUTPUT_BYTES].addAndGet(outputBytes);
        classStats[CS_LAST_OUTPUT].set(outputBytes);
        classStats[CS_LAST_FLAGS].set(flags);

        AtomicLong[] flagStats = BY_FLAGS.computeIfAbsent(flags, ignored -> atomics(FLAG_STAT_COUNT));
        flagStats[FS_BYTES_CALLS].incrementAndGet();
        flagStats[FS_BYTES_NANOS].addAndGet(elapsed);
        flagStats[FS_OUTPUT_BYTES].addAndGet(outputBytes);
    }

    private static void beginTiming(ThreadLocal<ArrayDeque<Object[]>> stackLocal) {
        Object[] context = currentTransform();
        boolean selected = Boolean.TRUE.equals(MIXIN_RESULTS.get().get(key((String) context[0], (String) context[1])));
        int depth = SELECTED_ACTIVE_DEPTH.get();
        boolean aggregate = selected && depth == 0;
        if (selected) {
            SELECTED_ACTIVE_DEPTH.set(depth + 1);
            if (!aggregate) {
                NESTED_SELECTED_SKIPPED.increment();
            }
        }
        stackLocal.get().addLast(new Object[] {context, selected, aggregate, System.nanoTime()});
    }

    private static Object[] endTiming(ThreadLocal<ArrayDeque<Object[]>> stackLocal) {
        ArrayDeque<Object[]> stack = stackLocal.get();
        Object[] frame = stack.removeLast();
        long elapsed = System.nanoTime() - (Long) frame[3];
        if ((Boolean) frame[1]) {
            SELECTED_ACTIVE_DEPTH.set(Math.max(0, SELECTED_ACTIVE_DEPTH.get() - 1));
        }
        frame[3] = elapsed;
        if (stack.isEmpty()) {
            stackLocal.remove();
        }
        return frame;
    }

    private static Object[] currentTransform() {
        Object[] context = TRANSFORM_CONTEXTS.get().peekLast();
        if (context == null) {
            throw new IllegalStateException("No active ClassTransformer context");
        }
        return context;
    }

    private static String key(String className, String reason) {
        return className + '\0' + reason;
    }

    private static AtomicLong[] atomics(int size) {
        AtomicLong[] values = new AtomicLong[size];
        for (int i = 0; i < size; i++) {
            values[i] = new AtomicLong();
        }
        return values;
    }

    public static void report(String reason) {
        long acceptNanos = ACCEPT_NANOS.sum();
        long bytesNanos = TO_BYTES_NANOS.sum();
        emit(String.format(
                Locale.ROOT,
                "summary=%s mixin_process_calls=%d mixin_rewrites=%d classes=%d accept_calls=%d accept_ms=%.3f to_bytes_calls=%d to_bytes_ms=%.3f tail_total_ms=%.3f input_mib=%.3f output_mib=%.3f max_accept_ms=%.3f max_to_bytes_ms=%.3f nested_selected_skipped=%d",
                reason,
                MIXIN_PROCESS_CALLS.sum(), MIXIN_REWRITES.sum(), BY_CLASS.size(), ACCEPT_CALLS.sum(),
                acceptNanos / 1_000_000.0, TO_BYTES_CALLS.sum(), bytesNanos / 1_000_000.0,
                (acceptNanos + bytesNanos) / 1_000_000.0, INPUT_BYTES.sum() / 1048576.0,
                OUTPUT_BYTES.sum() / 1048576.0, MAX_ACCEPT_NANOS.get() / 1_000_000.0,
                MAX_TO_BYTES_NANOS.get() / 1_000_000.0, NESTED_SELECTED_SKIPPED.sum()));

        ArrayList<Map.Entry<Integer, AtomicLong[]>> flags = new ArrayList<>(BY_FLAGS.entrySet());
        flags.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, AtomicLong[]> entry : flags) {
            int value = entry.getKey();
            AtomicLong[] s = entry.getValue();
            emit(String.format(Locale.ROOT,
                    "flags=%d compute_frames=%s compute_maxs=%s simple_rewrite=%s accept_calls=%d accept_ms=%.3f to_bytes_calls=%d to_bytes_ms=%.3f total_ms=%.3f input_mib=%.3f output_mib=%.3f",
                    value, (value & COMPUTE_FRAMES) != 0, (value & COMPUTE_MAXS) != 0,
                    (value & SIMPLE_REWRITE) != 0, s[FS_ACCEPT_CALLS].get(), s[FS_ACCEPT_NANOS].get() / 1_000_000.0,
                    s[FS_BYTES_CALLS].get(), s[FS_BYTES_NANOS].get() / 1_000_000.0,
                    (s[FS_ACCEPT_NANOS].get() + s[FS_BYTES_NANOS].get()) / 1_000_000.0,
                    s[FS_INPUT_BYTES].get() / 1048576.0, s[FS_OUTPUT_BYTES].get() / 1048576.0));
        }

        ArrayList<Map.Entry<String, AtomicLong[]>> classes = new ArrayList<>(BY_CLASS.entrySet());
        classes.sort(Comparator.<Map.Entry<String, AtomicLong[]>>comparingLong(e -> total(e.getValue())).reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("total", classes);
        classes.sort(Comparator.<Map.Entry<String, AtomicLong[]>>comparingLong(e -> e.getValue()[CS_ACCEPT_NANOS].get()).reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("accept", classes);
        classes.sort(Comparator.<Map.Entry<String, AtomicLong[]>>comparingLong(e -> e.getValue()[CS_BYTES_NANOS].get()).reversed()
                .thenComparing(Map.Entry::getKey));
        emitTop("to_bytes", classes);
    }

    private static long total(AtomicLong[] s) {
        return s[CS_ACCEPT_NANOS].get() + s[CS_BYTES_NANOS].get();
    }

    private static void emitTop(String dimension, ArrayList<Map.Entry<String, AtomicLong[]>> classes) {
        int count = Math.min(TOP_LIMIT, classes.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<String, AtomicLong[]> entry = classes.get(i);
            AtomicLong[] s = entry.getValue();
            int flags = (int) s[CS_LAST_FLAGS].get();
            emit(String.format(Locale.ROOT,
                    "dimension=%s rank=%d class=%s flags=%d compute_frames=%s compute_maxs=%s simple_rewrite=%s accept_calls=%d accept_ms=%.3f to_bytes_calls=%d to_bytes_ms=%.3f total_ms=%.3f input_bytes=%d output_bytes_last=%d output_bytes_sum=%d",
                    dimension, i + 1, entry.getKey(), flags, (flags & COMPUTE_FRAMES) != 0,
                    (flags & COMPUTE_MAXS) != 0, (flags & SIMPLE_REWRITE) != 0,
                    s[CS_ACCEPT_CALLS].get(), s[CS_ACCEPT_NANOS].get() / 1_000_000.0,
                    s[CS_BYTES_CALLS].get(), s[CS_BYTES_NANOS].get() / 1_000_000.0,
                    total(s) / 1_000_000.0, s[CS_INPUT_BYTES].get(), s[CS_LAST_OUTPUT].get(),
                    s[CS_OUTPUT_BYTES].get()));
        }
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MODLAUNCHER_TAIL " + payload);
    }
}
