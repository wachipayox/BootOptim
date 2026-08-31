package dev.wachipayox.bootoptim.profiling.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Diagnostic-only profiler for custom NeoForge geometry baking. */
public final class CustomGeometryBakeProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/CustomGeometryProfiler");
    private static final IdentityHashMap<IUnbakedGeometry<?>, GeometryIdentityStats> GEOMETRIES = new IdentityHashMap<>();
    private static final IdentityHashMap<IUnbakedGeometry<?>, String> STRUCTURAL_HINT_CACHE = new IdentityHashMap<>();
    private static final Map<String, BakeStats> BY_CLASS = new HashMap<>();
    private static final Map<String, BakeStats> BY_NAMESPACE = new HashMap<>();
    private static final Map<String, BakeStats> DECOCRAFT_STRUCTURAL_HINTS = new HashMap<>();

    private static boolean active;
    private static int totalCalls;
    private static long totalNanos;
    private static int geometryRepeatCalls;
    private static long geometryRepeatNanos;
    private static int geometryStateRepeatCalls;
    private static long geometryStateRepeatNanos;

    private CustomGeometryBakeProfiler() {
    }

    public static synchronized void begin() {
        active = true;
        GEOMETRIES.clear();
        STRUCTURAL_HINT_CACHE.clear();
        BY_CLASS.clear();
        BY_NAMESPACE.clear();
        DECOCRAFT_STRUCTURAL_HINTS.clear();
        totalCalls = 0;
        totalNanos = 0L;
        geometryRepeatCalls = 0;
        geometryRepeatNanos = 0L;
        geometryStateRepeatCalls = 0;
        geometryStateRepeatNanos = 0L;
    }

    public static BakedModel profile(
            BlockGeometryBakingContext context,
            IUnbakedGeometry<?> geometry,
            ModelState modelState,
            Supplier<BakedModel> bakeAction) {
        final boolean enabled;
        final boolean repeatedGeometry;
        final boolean repeatedGeometryState;
        final String modelName;
        final String namespace;
        final String structuralHint;

        synchronized (CustomGeometryBakeProfiler.class) {
            enabled = active;
            if (!enabled) {
                repeatedGeometry = false;
                repeatedGeometryState = false;
                modelName = "-";
                namespace = "-";
                structuralHint = null;
            } else {
                GeometryIdentityStats identity = GEOMETRIES.get(geometry);
                repeatedGeometry = identity != null;
                if (identity == null) {
                    identity = new GeometryIdentityStats();
                    GEOMETRIES.put(geometry, identity);
                }
                repeatedGeometryState = identity.states.put(modelState, Boolean.TRUE) != null;
                modelName = context.getModelName();
                namespace = namespace(modelName);
                structuralHint = "decocraft".equals(namespace)
                        ? STRUCTURAL_HINT_CACHE.computeIfAbsent(geometry, CustomGeometryBakeProfiler::structuralHint)
                        : null;
            }
        }

        if (!enabled) {
            return bakeAction.get();
        }

        long startedNanos = System.nanoTime();
        BakedModel result = bakeAction.get();
        long elapsedNanos = System.nanoTime() - startedNanos;

        synchronized (CustomGeometryBakeProfiler.class) {
            totalCalls++;
            totalNanos += elapsedNanos;
            if (repeatedGeometry) {
                geometryRepeatCalls++;
                geometryRepeatNanos += elapsedNanos;
            }
            if (repeatedGeometryState) {
                geometryStateRepeatCalls++;
                geometryStateRepeatNanos += elapsedNanos;
            }
            String className = geometry.getClass().getName();
            BY_CLASS.computeIfAbsent(className, ignored -> new BakeStats()).add(elapsedNanos, modelName);
            BY_NAMESPACE.computeIfAbsent(namespace, ignored -> new BakeStats()).add(elapsedNanos, modelName);
            if (structuralHint != null) {
                DECOCRAFT_STRUCTURAL_HINTS.computeIfAbsent(structuralHint, ignored -> new BakeStats())
                        .add(elapsedNanos, modelName);
            }
        }
        return result;
    }

    public static synchronized void finish() {
        if (!active) {
            return;
        }
        active = false;
        int distinctGeometryStates = GEOMETRIES.values().stream().mapToInt(stats -> stats.states.size()).sum();
        LOGGER.info(
                "BOOTOPTIM_CUSTOM_GEOMETRY_DISTRIBUTION total_calls={} total_ms={} distinct_geometry_identities={} geometry_repeat_calls={} geometry_repeat_ms={} geometry_repeat_time_percent={} distinct_geometry_state_identities={} geometry_state_repeat_calls={} geometry_state_repeat_ms={} geometry_state_repeat_time_percent={} decocraft_structural_hints={}",
                totalCalls,
                ms(totalNanos),
                GEOMETRIES.size(),
                geometryRepeatCalls,
                ms(geometryRepeatNanos),
                percent(geometryRepeatNanos, totalNanos),
                distinctGeometryStates,
                geometryStateRepeatCalls,
                ms(geometryStateRepeatNanos),
                percent(geometryStateRepeatNanos, totalNanos),
                DECOCRAFT_STRUCTURAL_HINTS.size());
        logTop("class", BY_CLASS, 15, false);
        logTop("namespace", BY_NAMESPACE, 20, false);
        logTop("decocraft_structural_hint", DECOCRAFT_STRUCTURAL_HINTS, 15, true);
    }

    private static void logTop(String dimension, Map<String, BakeStats> stats, int limit, boolean redactKey) {
        List<Map.Entry<String, BakeStats>> sorted = new ArrayList<>(stats.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, BakeStats>>comparingLong(entry -> entry.getValue().totalNanos).reversed());
        long allNanos = sorted.stream().mapToLong(entry -> entry.getValue().totalNanos).sum();
        int rank = 0;
        for (Map.Entry<String, BakeStats> entry : sorted) {
            if (++rank > limit) {
                break;
            }
            BakeStats value = entry.getValue();
            String key = redactKey ? Integer.toHexString(entry.getKey().hashCode()) : entry.getKey();
            LOGGER.info(
                    "BOOTOPTIM_CUSTOM_GEOMETRY_TOP dimension={} rank={} key={} calls={} total_ms={} share_percent={} avg_us={} max_us={} example_model={}{}",
                    dimension,
                    rank,
                    key,
                    value.calls,
                    ms(value.totalNanos),
                    percent(value.totalNanos, allNanos),
                    String.format(Locale.ROOT, "%.3f", value.calls == 0 ? 0.0 : value.totalNanos / 1_000.0 / value.calls),
                    String.format(Locale.ROOT, "%.3f", value.maxNanos / 1_000.0),
                    value.exampleModel,
                    redactKey ? " structural_hint=" + truncate(entry.getKey(), 240) : "");
        }
    }

    private static String namespace(String modelName) {
        int separator = modelName.indexOf(':');
        return separator > 0 ? modelName.substring(0, separator) : "unknown";
    }

    /**
     * Best-effort diagnostic fingerprint. This is deliberately not a cache key: it only exposes stable scalar
     * fields from Decocraft objects so we can see whether separately-instantiated geometries point at the same
     * underlying model/material metadata.
     */
    private static String structuralHint(IUnbakedGeometry<?> geometry) {
        StringBuilder out = new StringBuilder(geometry.getClass().getName());
        appendFields(geometry, out, 0, new IdentityHashMap<>());
        return out.toString();
    }

    private static void appendFields(Object object, StringBuilder out, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (object == null || depth > 2 || seen.put(object, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = object.getClass();
        Field[] fields = type.getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                if (!field.trySetAccessible()) {
                    continue;
                }
                Object value = field.get(object);
                if (value == null || isScalar(value)) {
                    out.append('|').append(type.getSimpleName()).append('.').append(field.getName()).append('=').append(value);
                } else if (value.getClass().getName().startsWith("com.razz.decocraft.") && depth < 2) {
                    out.append('|').append(type.getSimpleName()).append('.').append(field.getName()).append("{");
                    appendFields(value, out, depth + 1, seen);
                    out.append('}');
                }
            } catch (RuntimeException | IllegalAccessException ignored) {
                // Diagnostic only: inaccessible fields simply make the hint less specific.
            }
        }
    }

    private static boolean isScalar(Object value) {
        Class<?> type = value.getClass();
        return type == String.class
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof ResourceLocation;
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0 : part * 100.0 / total);
    }

    private static final class GeometryIdentityStats {
        final IdentityHashMap<ModelState, Boolean> states = new IdentityHashMap<>();
    }

    private static final class BakeStats {
        int calls;
        long totalNanos;
        long maxNanos;
        String exampleModel = "-";

        void add(long nanos, String modelName) {
            calls++;
            totalNanos += nanos;
            if (exampleModel.equals("-")) {
                exampleModel = modelName;
            }
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
        }
    }
}
