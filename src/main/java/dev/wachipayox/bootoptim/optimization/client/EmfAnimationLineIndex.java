package dev.wachipayox.bootoptim.optimization.client;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EMF {@code EMFAnimationHandler.getLine(key)} returns the first animation line with that key by
 * scanning the whole list, and {@code ASMAnimationHandler.finishAndValidate} calls it once per
 * written variable. Inside that call (no line is added during it) the lines are indexed once by
 * key, keeping the first duplicate as the scan does. Guarded by
 * {@link EmfExpressionParsing#LINE_INDEX}: sampled calls run the scan and compare identities.
 */
public final class EmfAnimationLineIndex {
    private static final int MAX_LINES = 4096;
    private static final Object NONE = new Object();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Object> PENDING = new ThreadLocal<>();
    private static volatile Field animKey;

    private EmfAnimationLineIndex() {}

    private static final class Scope {
        private final Object owner;
        private boolean attempted;
        private Map<String, Object> byKey;

        private Scope(Object owner) {
            this.owner = owner;
        }
    }

    public record Lookup(Object line) {}

    /** Opens a scope for {@code owner}; returns the previous scope for {@link #close}. */
    public static Object open(Object owner) {
        Scope previous = SCOPE.get();
        SCOPE.set(new Scope(owner));
        return previous;
    }

    public static void close(Object previous) {
        if (previous == null) {
            SCOPE.remove();
        } else {
            SCOPE.set((Scope) previous);
        }
    }

    /** The indexed answer for {@code key}, or null to run the stock scan. */
    public static Lookup lookup(Object handler, List<?> lines, String key) {
        PENDING.remove();
        Scope scope = SCOPE.get();
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.LINE_INDEX;
        if (scope == null || scope.owner != handler || lines == null || !guard.enabled()) {
            return null;
        }
        if (!scope.attempted) {
            scope.attempted = true;
            scope.byKey = index(lines);
        }
        if (scope.byKey == null) {
            return null;
        }
        Object line = scope.byKey.get(key);
        if (guard.verifyThisCall()) {
            PENDING.set(line == null ? NONE : line);
            return null;
        }
        return new Lookup(line);
    }

    /** At the end of a stock scan that {@link #lookup} chose to verify. */
    public static void check(Object stock) {
        Object expected = PENDING.get();
        if (expected == null) {
            return;
        }
        PENDING.remove();
        if (stock == (expected == NONE ? null : expected)) {
            EmfExpressionParsing.LINE_INDEX.confirmed();
        } else {
            EmfExpressionParsing.LINE_INDEX.mismatch("line_differs");
        }
    }

    private static Map<String, Object> index(List<?> lines) {
        if (lines.size() > MAX_LINES) {
            return null;
        }
        Map<String, Object> byKey = new HashMap<>(lines.size() * 2);
        try {
            for (Object line : lines) {
                if (line == null) {
                    return null; // The scan would fail on it: keep that path stock.
                }
                Field field = animKey;
                if (field == null || field.getDeclaringClass() != line.getClass()) {
                    field = line.getClass().getField("animKey");
                    animKey = field;
                }
                if (!(field.get(line) instanceof String name)) {
                    return null;
                }
                byKey.putIfAbsent(name, line);
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            EmfExpressionParsing.LINE_INDEX.disable("anim_key_unavailable");
            return null;
        }
        return byKey;
    }
}
