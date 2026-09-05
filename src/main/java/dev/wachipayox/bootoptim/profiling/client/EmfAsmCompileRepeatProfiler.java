package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * Diagnostic-only attribution of repeated EMF 3.2.x ASM compilation work while renderer providers
 * are reconstructed. It never reuses or changes an EMF parse tree, executor, variable binding, or
 * model part.
 */
public final class EmfAsmCompileRepeatProfiler {
    public static final String PROPERTY = "boot_optim.profileEmfAsmCompileRepeat";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final AtomicReference<State> ACTIVE = new AtomicReference<>();
    private static final ThreadLocal<Deque<PendingCompile>> PENDING =
            ThreadLocal.withInitial(ArrayDeque::new);

    private EmfAsmCompileRepeatProfiler() {}

    public enum Scope {
        BLOCK_ENTITY("block_entity"),
        ENTITY("entity");

        private final String markerName;

        Scope(String markerName) {
            this.markerName = markerName;
        }
    }

    public static void begin(Scope scope) {
        if (!ENABLED) {
            return;
        }

        State state = new State(scope);
        State previous = ACTIVE.getAndSet(state);
        if (previous != null) {
            LOGGER.warn(
                    "BOOTOPTIM_EMF_ASM_REPEAT status=scope_overlap previous={} next={}",
                    previous.scope.markerName,
                    scope.markerName);
        }
    }

    public static void end(Scope scope) {
        if (!ENABLED) {
            return;
        }

        State state = ACTIVE.getAndSet(null);
        if (state == null) {
            return;
        }
        if (state.scope != scope) {
            LOGGER.warn(
                    "BOOTOPTIM_EMF_ASM_REPEAT status=scope_mismatch expected={} actual={}",
                    scope.markerName,
                    state.scope.markerName);
        }
        state.log();
    }

    /** Called immediately before EMF's stock ASMParser.compileOrNull body. */
    public static void beginCompile(Object animationHandler) {
        State state = ACTIVE.get();
        if (!ENABLED || state == null) {
            return;
        }

        String sourceSignature;
        try {
            sourceSignature = sourceSignature(animationHandler);
        } catch (ReflectiveOperationException | RuntimeException e) {
            sourceSignature = "reflection_error";
            synchronized (state) {
                state.reflectionFailures++;
            }
        }

        // Start after diagnostic reflection so compile_ns measures the original EMF body, not our
        // source-signature construction overhead.
        PENDING.get().push(new PendingCompile(state, sourceSignature, System.nanoTime()));
    }

    /** Called at RETURN from EMF's stock ASMParser.compileOrNull body. */
    public static void endCompile(Object animationHandler, Object variableHandler, Object executor) {
        if (!ENABLED) {
            return;
        }

        Deque<PendingCompile> stack = PENDING.get();
        if (stack.isEmpty()) {
            return;
        }

        // Stop timing before any diagnostic reflection performed below.
        long endNanos = System.nanoTime();
        PendingCompile pending = stack.pop();
        State state = pending.state;
        long durationNanos = Math.max(0L, endNanos - pending.startNanos);

        String resolvedSignature = null;
        if (executor != null) {
            try {
                resolvedSignature = resolvedSignature(
                        pending.sourceSignature, animationHandler, variableHandler);
            } catch (ReflectiveOperationException | RuntimeException e) {
                synchronized (state) {
                    state.reflectionFailures++;
                }
            }
        }

        synchronized (state) {
            state.record(pending.sourceSignature, resolvedSignature, durationNanos, executor == null);
        }
    }

    private static String sourceSignature(Object animationHandler)
            throws ReflectiveOperationException {
        List<?> lines = lines(animationHandler);
        StringBuilder signature = new StringBuilder(lines.size() * 64);
        signature.append("lines=").append(lines.size()).append(';');
        for (Object line : lines) {
            Class<?> lineClass = line.getClass();
            signature
                    .append(readPublicField(lineClass, line, "animKey"))
                    .append('=')
                    .append(readPublicField(lineClass, line, "expression"))
                    .append('|')
                    .append(readPublicField(lineClass, line, "isBoolean"))
                    .append(';');
        }
        return sha256(signature.toString());
    }

    /**
     * Conservative candidate template signature after stock parsing/ASM code generation.
     *
     * <p>It includes source text, EMF's resolved float/bool variable layout, read/write roles and
     * output array indexes assigned to each animation line. Equality here is deliberately treated
     * only as a candidate for later canonical-bytecode proof, not as proof that executors are
     * interchangeable.</p>
     */
    private static String resolvedSignature(
            String sourceSignature, Object animationHandler, Object variableHandler)
            throws ReflectiveOperationException {
        Class<?> varClass = variableHandler.getClass();
        Method getFloatVarList = varClass.getMethod("getFloatVarList");
        Method getBoolVarList = varClass.getMethod("getBoolVarList");
        Method isReadVarName = varClass.getMethod("isReadVarName", String.class);
        Method isWriteVarName = varClass.getMethod("isWriteVarName", String.class);

        @SuppressWarnings("unchecked")
        List<String> floats = (List<String>) getFloatVarList.invoke(variableHandler);
        @SuppressWarnings("unchecked")
        List<String> bools = (List<String>) getBoolVarList.invoke(variableHandler);

        StringBuilder signature = new StringBuilder(sourceSignature).append('|');
        appendVariables(signature, 'F', floats, variableHandler, isReadVarName, isWriteVarName);
        appendVariables(signature, 'B', bools, variableHandler, isReadVarName, isWriteVarName);

        signature.append("OUT[");
        for (Object line : lines(animationHandler)) {
            Class<?> lineClass = line.getClass();
            signature
                    .append(readPublicField(lineClass, line, "animKey"))
                    .append(':')
                    .append(readPublicField(lineClass, line, "isBoolean"))
                    .append(':')
                    .append(readPublicField(lineClass, line, "asmIndex"))
                    .append(';');
        }
        signature.append(']');
        return sha256(signature.toString());
    }

    private static void appendVariables(
            StringBuilder signature,
            char type,
            List<String> variables,
            Object variableHandler,
            Method isReadVarName,
            Method isWriteVarName)
            throws ReflectiveOperationException {
        signature.append(type).append('[');
        for (String variable : variables) {
            boolean read = (boolean) isReadVarName.invoke(variableHandler, variable);
            boolean write = (boolean) isWriteVarName.invoke(variableHandler, variable);
            signature
                    .append(variable)
                    .append(':')
                    .append(read ? 'R' : '-')
                    .append(write ? 'W' : '-')
                    .append(';');
        }
        signature.append(']');
    }

    @SuppressWarnings("unchecked")
    private static List<?> lines(Object animationHandler) throws ReflectiveOperationException {
        Method lines = animationHandler.getClass().getMethod("lines");
        return (List<?>) lines.invoke(animationHandler);
    }

    private static Object readPublicField(Class<?> owner, Object target, String name)
            throws ReflectiveOperationException {
        Field field = owner.getField(name);
        return field.get(target);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : hashed) {
                result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record PendingCompile(State state, String sourceSignature, long startNanos) {}

    private static final class SignatureStat {
        private int calls;
        private long totalNanos;
        private long repeatedNanos;

        void record(long durationNanos) {
            calls++;
            totalNanos += durationNanos;
            if (calls > 1) {
                repeatedNanos += durationNanos;
            }
        }
    }

    private static final class State {
        private final Scope scope;
        private final Map<String, SignatureStat> sourceStats = new HashMap<>();
        private final Map<String, SignatureStat> resolvedStats = new HashMap<>();
        private int calls;
        private int failures;
        private int reflectionFailures;
        private long compileNanos;

        State(Scope scope) {
            this.scope = scope;
        }

        void record(
                String sourceSignature,
                String resolvedSignature,
                long durationNanos,
                boolean failed) {
            calls++;
            compileNanos += durationNanos;
            sourceStats.computeIfAbsent(sourceSignature, ignored -> new SignatureStat())
                    .record(durationNanos);
            if (failed) {
                failures++;
            } else if (resolvedSignature != null) {
                resolvedStats.computeIfAbsent(resolvedSignature, ignored -> new SignatureStat())
                        .record(durationNanos);
            }
        }

        void log() {
            long sourceRepeatedNanos = repeatedNanos(sourceStats);
            long resolvedRepeatedNanos = repeatedNanos(resolvedStats);
            int sourceRepeatCalls = repeatCalls(sourceStats);
            int resolvedRepeatCalls = repeatCalls(resolvedStats);

            LOGGER.info(
                    "BOOTOPTIM_EMF_ASM_REPEAT scope={} calls={} compile_ms={} failures={} reflection_failures={} "
                            + "source_unique={} source_repeat_calls={} source_repeat_ms={} "
                            + "template_candidate_unique={} template_candidate_repeat_calls={} "
                            + "template_candidate_repeat_ms={} top_template_candidates={}",
                    scope.markerName,
                    calls,
                    ms(compileNanos),
                    failures,
                    reflectionFailures,
                    sourceStats.size(),
                    sourceRepeatCalls,
                    ms(sourceRepeatedNanos),
                    resolvedStats.size(),
                    resolvedRepeatCalls,
                    ms(resolvedRepeatedNanos),
                    topResolvedCandidates(resolvedStats));
        }

        private static long repeatedNanos(Map<String, SignatureStat> stats) {
            long total = 0L;
            for (SignatureStat stat : stats.values()) {
                total += stat.repeatedNanos;
            }
            return total;
        }

        private static int repeatCalls(Map<String, SignatureStat> stats) {
            int repeats = 0;
            for (SignatureStat stat : stats.values()) {
                repeats += Math.max(0, stat.calls - 1);
            }
            return repeats;
        }

        private static String topResolvedCandidates(Map<String, SignatureStat> stats) {
            List<Map.Entry<String, SignatureStat>> entries = new ArrayList<>(stats.entrySet());
            entries.removeIf(entry -> entry.getValue().calls < 2);
            entries.sort(Comparator.comparingLong(
                            (Map.Entry<String, SignatureStat> entry) -> entry.getValue().repeatedNanos)
                    .reversed());

            StringBuilder result = new StringBuilder();
            int count = Math.min(5, entries.size());
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    result.append(',');
                }
                Map.Entry<String, SignatureStat> entry = entries.get(i);
                SignatureStat stat = entry.getValue();
                result.append(entry.getKey(), 0, 12)
                        .append(':')
                        .append(stat.calls)
                        .append(':')
                        .append(ms(stat.repeatedNanos));
            }
            return result.length() == 0 ? "none" : result.toString();
        }

        private static String ms(long nanos) {
            return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
        }
    }
}
