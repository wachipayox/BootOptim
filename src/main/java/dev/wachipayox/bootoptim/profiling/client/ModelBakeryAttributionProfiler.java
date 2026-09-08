package dev.wachipayox.bootoptim.profiling.client;

import java.net.URI;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Profile-only attribution for the work that survives ModelBakerImpl's real baked-model cache.
 *
 * <p>The profiler computes same-thread exclusive time by subtracting nested bakeUncached spans.
 * It never caches or replaces a model result. Identity tracking is diagnostic only and is limited
 * to custom-geometry objects, deliberately avoiding the already-rejected top-level model-identity
 * cache premise from PR #36.</p>
 */
public final class ModelBakeryAttributionProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelBakeryAttribution");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileModelBakeryAttribution");
    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<String, Stats> BY_FAMILY = new HashMap<>();
    private static final Map<String, Stats> BY_NAMESPACE = new HashMap<>();
    private static final Map<String, Stats> BY_OWNER = new HashMap<>();
    private static final Map<String, GeometryStats> BY_GEOMETRY = new HashMap<>();
    private static final IdentityHashMap<Object, Boolean> SEEN_GEOMETRY = new IdentityHashMap<>();
    private static final ConcurrentHashMap<Class<?>, String> CLASS_OWNER = new ConcurrentHashMap<>();

    private static volatile boolean active;
    private static long calls;
    private static long topLevelCalls;
    private static long nestedCalls;
    private static long exclusiveNanos;
    private static long topLevelExclusiveNanos;
    private static long nestedExclusiveNanos;
    private static long abandonedFrames;
    private static long corruptFrames;

    private ModelBakeryAttributionProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static synchronized void beginBakeModels() {
        if (!ENABLED || active) return;
        BY_FAMILY.clear();
        BY_NAMESPACE.clear();
        BY_OWNER.clear();
        BY_GEOMETRY.clear();
        SEEN_GEOMETRY.clear();
        calls = 0L;
        topLevelCalls = 0L;
        nestedCalls = 0L;
        exclusiveNanos = 0L;
        topLevelExclusiveNanos = 0L;
        nestedExclusiveNanos = 0L;
        abandonedFrames = 0L;
        corruptFrames = 0L;
        STACK.remove();
        active = true;
    }

    public static void beginUncached(UnbakedModel model) {
        if (!active) return;
        Deque<Frame> stack = STACK.get();
        boolean topLevel = stack.isEmpty();
        Object geometry = customGeometry(model);
        boolean repeatedGeometry = false;
        if (geometry != null) {
            synchronized (ModelBakeryAttributionProfiler.class) {
                repeatedGeometry = SEEN_GEOMETRY.put(geometry, Boolean.TRUE) != null;
            }
        }
        String family = family(model, geometry);
        String namespace = namespace(model);
        String owner = owner(model, geometry);
        stack.push(new Frame(model, geometry, family, namespace, owner, topLevel, repeatedGeometry, System.nanoTime()));
        synchronized (ModelBakeryAttributionProfiler.class) {
            calls++;
            if (topLevel) topLevelCalls++; else nestedCalls++;
        }
    }

    public static void endUncached(UnbakedModel model) {
        if (!active) return;
        Deque<Frame> stack = STACK.get();
        Frame frame = stack.poll();
        if (frame == null || frame.model != model) {
            synchronized (ModelBakeryAttributionProfiler.class) {
                corruptFrames++;
                abandonedFrames += stack.size();
            }
            stack.clear();
            return;
        }

        long elapsed = System.nanoTime() - frame.startedNanos;
        long exclusive = Math.max(0L, elapsed - frame.childNanos);
        Frame parent = stack.peek();
        if (parent != null) parent.childNanos += elapsed;

        synchronized (ModelBakeryAttributionProfiler.class) {
            exclusiveNanos += exclusive;
            if (frame.topLevel) topLevelExclusiveNanos += exclusive; else nestedExclusiveNanos += exclusive;
            BY_FAMILY.computeIfAbsent(frame.family, ignored -> new Stats()).add(exclusive, frame.topLevel);
            BY_NAMESPACE.computeIfAbsent(frame.namespace, ignored -> new Stats()).add(exclusive, frame.topLevel);
            BY_OWNER.computeIfAbsent(frame.owner, ignored -> new Stats()).add(exclusive, frame.topLevel);
            if (frame.geometry != null) {
                BY_GEOMETRY.computeIfAbsent(frame.family, ignored -> new GeometryStats())
                        .add(exclusive, frame.repeatedGeometry);
            }
        }
    }

    public static synchronized void finishBakeModels() {
        if (!active) return;
        active = false;
        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) {
            abandonedFrames += stack.size();
            stack.clear();
        }
        STACK.remove();

        LOGGER.info(
                "BOOTOPTIM_MODEL_BAKERY_ATTRIBUTION kind=summary calls={} top_level_calls={} nested_calls={} exclusive_ms={} top_level_exclusive_ms={} nested_exclusive_ms={} abandoned_frames={} corrupt_frames={}",
                calls, topLevelCalls, nestedCalls, ms(exclusiveNanos), ms(topLevelExclusiveNanos),
                ms(nestedExclusiveNanos), abandonedFrames, corruptFrames);
        logTop("family", BY_FAMILY, 24);
        logTop("namespace", BY_NAMESPACE, 24);
        logTop("owner", BY_OWNER, 24);
        logGeometry(24);
    }

    private static Object customGeometry(UnbakedModel model) {
        if (!(model instanceof BlockModel blockModel) || !blockModel.customData.hasCustomGeometry()) return null;
        return blockModel.customData.getCustomGeometry();
    }

    private static String family(UnbakedModel model, Object geometry) {
        if (geometry != null) return "custom_geometry:" + geometry.getClass().getName();
        if (model instanceof BlockModel blockModel) {
            BlockModel root = blockModel.getRootModel();
            if (root == ModelBakery.GENERATION_MARKER) return "block_model:generated_item";
            if (root == ModelBakery.BLOCK_ENTITY_MARKER) return "block_model:builtin_entity";
            return "block_model:elements";
        }
        Class<?> type = model.getClass();
        if (type == MultiPart.class) return "vanilla:multipart";
        if (type == MultiVariant.class) return "vanilla:multivariant";
        return "unbaked:" + type.getName();
    }

    private static String namespace(UnbakedModel model) {
        if (model instanceof BlockModel blockModel) {
            String name = blockModel.name;
            int separator = name.indexOf(':');
            if (separator > 0) return name.substring(0, separator);
        }
        return "unknown";
    }

    private static String owner(UnbakedModel model, Object geometry) {
        Class<?> type = geometry != null ? geometry.getClass() : model.getClass();
        return type.getName() + "@" + CLASS_OWNER.computeIfAbsent(type, ModelBakeryAttributionProfiler::codeSourceName);
    }

    private static String codeSourceName(Class<?> type) {
        try {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) return "no-code-source";
            URI uri = codeSource.getLocation().toURI();
            String path = uri.getPath();
            if (path == null || path.isBlank()) return uri.toString();
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String leaf = slash >= 0 ? path.substring(slash + 1) : path;
            return leaf.isBlank() ? path : leaf;
        } catch (Throwable ignored) {
            return "code-source-error";
        }
    }

    private static void logTop(String dimension, Map<String, Stats> map, int limit) {
        List<Map.Entry<String, Stats>> rows = new ArrayList<>(map.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Stats>>comparingLong(entry -> entry.getValue().nanos).reversed());
        long total = rows.stream().mapToLong(entry -> entry.getValue().nanos).sum();
        int rank = 0;
        for (Map.Entry<String, Stats> entry : rows) {
            if (++rank > limit) break;
            Stats stats = entry.getValue();
            LOGGER.info(
                    "BOOTOPTIM_MODEL_BAKERY_ATTRIBUTION kind=top dimension={} rank={} key={} calls={} top_level_calls={} nested_calls={} exclusive_ms={} share_percent={} avg_us={} max_us={}",
                    dimension, rank, sanitize(entry.getKey()), stats.calls, stats.topLevelCalls,
                    stats.calls - stats.topLevelCalls, ms(stats.nanos), percent(stats.nanos, total),
                    micros(stats.calls == 0L ? 0L : stats.nanos / stats.calls), micros(stats.maxNanos));
        }
    }

    private static void logGeometry(int limit) {
        List<Map.Entry<String, GeometryStats>> rows = new ArrayList<>(BY_GEOMETRY.entrySet());
        rows.sort(Comparator.<Map.Entry<String, GeometryStats>>comparingLong(entry -> entry.getValue().nanos).reversed());
        int rank = 0;
        for (Map.Entry<String, GeometryStats> entry : rows) {
            if (++rank > limit) break;
            GeometryStats stats = entry.getValue();
            LOGGER.info(
                    "BOOTOPTIM_MODEL_BAKERY_ATTRIBUTION kind=geometry_identity rank={} key={} calls={} first_calls={} repeat_calls={} exclusive_ms={} first_exclusive_ms={} repeat_exclusive_ms={} repeat_share_percent={}",
                    rank, sanitize(entry.getKey()), stats.calls, stats.calls - stats.repeatCalls, stats.repeatCalls,
                    ms(stats.nanos), ms(stats.nanos - stats.repeatNanos), ms(stats.repeatNanos),
                    percent(stats.repeatNanos, stats.nanos));
        }
    }

    private static String sanitize(String value) {
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String micros(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000.0D);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }

    private static final class Frame {
        final UnbakedModel model;
        final Object geometry;
        final String family;
        final String namespace;
        final String owner;
        final boolean topLevel;
        final boolean repeatedGeometry;
        final long startedNanos;
        long childNanos;

        Frame(UnbakedModel model, Object geometry, String family, String namespace, String owner,
                boolean topLevel, boolean repeatedGeometry, long startedNanos) {
            this.model = model;
            this.geometry = geometry;
            this.family = family;
            this.namespace = namespace;
            this.owner = owner;
            this.topLevel = topLevel;
            this.repeatedGeometry = repeatedGeometry;
            this.startedNanos = startedNanos;
        }
    }

    private static class Stats {
        long calls;
        long topLevelCalls;
        long nanos;
        long maxNanos;

        void add(long value, boolean topLevel) {
            calls++;
            if (topLevel) topLevelCalls++;
            nanos += value;
            maxNanos = Math.max(maxNanos, value);
        }
    }

    private static final class GeometryStats {
        long calls;
        long repeatCalls;
        long nanos;
        long repeatNanos;

        void add(long value, boolean repeated) {
            calls++;
            nanos += value;
            if (repeated) {
                repeatCalls++;
                repeatNanos += value;
            }
        }
    }
}
