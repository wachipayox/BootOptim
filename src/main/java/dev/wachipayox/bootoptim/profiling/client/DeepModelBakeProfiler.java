package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Diagnostic-only profiler below the top-level model-bake loop.
 *
 * <p>Older BootOptim experiments already proved that top-level object-identity repetition is abundant but cheap.
 * This profiler instead measures the cache that actually guards recursive model baking and the exclusive cost of
 * {@code ModelBakerImpl#bakeUncached}. Nested uncached work is subtracted from its parent so class/category totals do
 * not double-count recursive bakes.</p>
 */
public final class DeepModelBakeProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DeepModelBake");
    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ModelResourceLocation> TOP_LEVEL = new ThreadLocal<>();

    private static final Map<String, Stats> BY_CATEGORY = new HashMap<>();
    private static final Map<String, Stats> BY_NAMESPACE = new HashMap<>();

    private static volatile boolean active;
    private static long cacheLookups;
    private static long cacheHits;
    private static long cacheMisses;
    private static long uncachedCalls;
    private static long topLevelUncachedCalls;
    private static long nestedUncachedCalls;
    private static long exclusiveNanos;
    private static long topLevelExclusiveNanos;
    private static long nestedExclusiveNanos;
    private static long abandonedFrames;
    private static long corruptFrames;
    private static int maxDepth;

    private DeepModelBakeProfiler() {
    }

    public static synchronized void begin() {
        BY_CATEGORY.clear();
        BY_NAMESPACE.clear();
        cacheLookups = 0L;
        cacheHits = 0L;
        cacheMisses = 0L;
        uncachedCalls = 0L;
        topLevelUncachedCalls = 0L;
        nestedUncachedCalls = 0L;
        exclusiveNanos = 0L;
        topLevelExclusiveNanos = 0L;
        nestedExclusiveNanos = 0L;
        abandonedFrames = 0L;
        corruptFrames = 0L;
        maxDepth = 0;
        STACK.remove();
        TOP_LEVEL.remove();
        active = true;
    }

    public static void profileTopLevelLoop(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> bakeAction) {
        if (!active) {
            models.forEach(bakeAction);
            return;
        }

        models.forEach((location, model) -> {
            TOP_LEVEL.set(location);
            Deque<Frame> stack = STACK.get();
            if (!stack.isEmpty()) {
                synchronized (DeepModelBakeProfiler.class) {
                    abandonedFrames += stack.size();
                }
                stack.clear();
            }
            try {
                bakeAction.accept(location, model);
            } finally {
                if (!stack.isEmpty()) {
                    synchronized (DeepModelBakeProfiler.class) {
                        abandonedFrames += stack.size();
                    }
                    stack.clear();
                }
                TOP_LEVEL.remove();
            }
        });
    }

    /** Records the real 1.21.1 recursive baked-cache lookup, not top-level object identity. */
    public static void cacheLookup(boolean hit) {
        if (!active) {
            return;
        }
        synchronized (DeepModelBakeProfiler.class) {
            cacheLookups++;
            if (hit) {
                cacheHits++;
            } else {
                cacheMisses++;
            }
        }
    }

    public static void beginUncached(UnbakedModel model) {
        if (!active) {
            return;
        }

        Deque<Frame> stack = STACK.get();
        boolean topLevel = stack.isEmpty();
        int depth = stack.size();
        ModelResourceLocation topLevelLocation = TOP_LEVEL.get();
        String category = category(model);
        String namespace = namespace(model, topLevelLocation);
        stack.push(new Frame(model, System.nanoTime(), category, namespace, topLevel));

        synchronized (DeepModelBakeProfiler.class) {
            uncachedCalls++;
            if (topLevel) {
                topLevelUncachedCalls++;
            } else {
                nestedUncachedCalls++;
            }
            maxDepth = Math.max(maxDepth, depth);
        }
    }

    public static void endUncached(UnbakedModel model) {
        if (!active) {
            return;
        }

        Deque<Frame> stack = STACK.get();
        Frame frame = stack.poll();
        if (frame == null || frame.model != model) {
            synchronized (DeepModelBakeProfiler.class) {
                corruptFrames++;
                abandonedFrames += stack.size();
            }
            stack.clear();
            return;
        }

        long elapsed = System.nanoTime() - frame.startedNanos;
        long exclusive = Math.max(0L, elapsed - frame.childNanos);
        Frame parent = stack.peek();
        if (parent != null) {
            parent.childNanos += elapsed;
        }

        synchronized (DeepModelBakeProfiler.class) {
            exclusiveNanos += exclusive;
            if (frame.topLevel) {
                topLevelExclusiveNanos += exclusive;
            } else {
                nestedExclusiveNanos += exclusive;
            }
            BY_CATEGORY.computeIfAbsent(frame.category, ignored -> new Stats()).add(exclusive, frame.topLevel);
            BY_NAMESPACE.computeIfAbsent(frame.namespace, ignored -> new Stats()).add(exclusive, frame.topLevel);
        }
    }

    public static synchronized void finish() {
        if (!active) {
            return;
        }
        active = false;

        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) {
            abandonedFrames += stack.size();
            stack.clear();
        }
        STACK.remove();
        TOP_LEVEL.remove();

        long unexplainedNestedUncached = Math.max(0L, nestedUncachedCalls - cacheMisses);
        LOGGER.info(
                "BOOTOPTIM_DEEP_MODEL_BAKE cache_lookups={} cache_hits={} cache_misses={} cache_hit_percent={} uncached_calls={} top_level_uncached={} nested_uncached={} nested_uncached_minus_cache_misses={} exclusive_ms={} top_level_exclusive_ms={} nested_exclusive_ms={} max_depth={} abandoned_frames={} corrupt_frames={}",
                cacheLookups,
                cacheHits,
                cacheMisses,
                percent(cacheHits, cacheLookups),
                uncachedCalls,
                topLevelUncachedCalls,
                nestedUncachedCalls,
                unexplainedNestedUncached,
                ms(exclusiveNanos),
                ms(topLevelExclusiveNanos),
                ms(nestedExclusiveNanos),
                maxDepth,
                abandonedFrames,
                corruptFrames);

        logTop("category", BY_CATEGORY, 25);
        logTop("namespace", BY_NAMESPACE, 25);
    }

    private static String category(UnbakedModel model) {
        if (!(model instanceof BlockModel blockModel)) {
            return model.getClass().getName();
        }

        if (blockModel.customData.hasCustomGeometry()) {
            Object geometry = blockModel.customData.getCustomGeometry();
            return geometry == null ? "BlockModel/custom" : "BlockModel/custom:" + geometry.getClass().getName();
        }

        BlockModel root = blockModel.getRootModel();
        if (root == ModelBakery.GENERATION_MARKER) {
            return "BlockModel/generated_item";
        }
        if (root == ModelBakery.BLOCK_ENTITY_MARKER) {
            return "BlockModel/builtin_entity";
        }
        return "BlockModel/elements";
    }

    private static String namespace(UnbakedModel model, ModelResourceLocation topLevelLocation) {
        if (model instanceof BlockModel blockModel) {
            String name = blockModel.name;
            int separator = name.indexOf(':');
            if (separator > 0) {
                return name.substring(0, separator);
            }
        }
        if (topLevelLocation != null) {
            return topLevelLocation.id().getNamespace();
        }
        return "unknown";
    }

    private static void logTop(String dimension, Map<String, Stats> stats, int limit) {
        List<Map.Entry<String, Stats>> sorted = new ArrayList<>(stats.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Stats>>comparingLong(entry -> entry.getValue().exclusiveNanos).reversed());
        long total = sorted.stream().mapToLong(entry -> entry.getValue().exclusiveNanos).sum();
        int rank = 0;
        for (Map.Entry<String, Stats> entry : sorted) {
            if (++rank > limit) {
                break;
            }
            Stats value = entry.getValue();
            LOGGER.info(
                    "BOOTOPTIM_DEEP_MODEL_BAKE_TOP dimension={} rank={} key={} calls={} top_level_calls={} nested_calls={} exclusive_ms={} share_percent={} avg_us={} max_us={}",
                    dimension,
                    rank,
                    entry.getKey(),
                    value.calls,
                    value.topLevelCalls,
                    value.calls - value.topLevelCalls,
                    ms(value.exclusiveNanos),
                    percent(value.exclusiveNanos, total),
                    String.format(Locale.ROOT, "%.3f", value.calls == 0 ? 0.0 : value.exclusiveNanos / 1_000.0 / value.calls),
                    String.format(Locale.ROOT, "%.3f", value.maxExclusiveNanos / 1_000.0));
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0 : part * 100.0 / total);
    }

    private static final class Frame {
        final UnbakedModel model;
        final long startedNanos;
        final String category;
        final String namespace;
        final boolean topLevel;
        long childNanos;

        Frame(UnbakedModel model, long startedNanos, String category, String namespace, boolean topLevel) {
            this.model = model;
            this.startedNanos = startedNanos;
            this.category = category;
            this.namespace = namespace;
            this.topLevel = topLevel;
        }
    }

    private static final class Stats {
        long calls;
        long topLevelCalls;
        long exclusiveNanos;
        long maxExclusiveNanos;

        void add(long nanos, boolean topLevel) {
            calls++;
            if (topLevel) {
                topLevelCalls++;
            }
            exclusiveNanos += nanos;
            maxExclusiveNanos = Math.max(maxExclusiveNanos, nanos);
        }
    }
}
