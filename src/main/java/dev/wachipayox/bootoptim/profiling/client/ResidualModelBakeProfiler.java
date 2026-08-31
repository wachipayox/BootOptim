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

/** Diagnostic-only exclusive uncached-bake attribution after production model optimizations. */
public final class ResidualModelBakeProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ResidualModelBake");
    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ModelResourceLocation> TOP_LEVEL = new ThreadLocal<>();
    private static final Map<String, Stats> BY_CATEGORY = new HashMap<>();
    private static final Map<String, Stats> BY_NAMESPACE = new HashMap<>();

    private static volatile boolean active;
    private static long calls;
    private static long topLevelCalls;
    private static long nestedCalls;
    private static long exclusiveNanos;
    private static long topLevelExclusiveNanos;
    private static long nestedExclusiveNanos;
    private static long abandonedFrames;
    private static long corruptFrames;

    private ResidualModelBakeProfiler() {}

    public static synchronized void begin() {
        BY_CATEGORY.clear();
        BY_NAMESPACE.clear();
        calls = topLevelCalls = nestedCalls = 0L;
        exclusiveNanos = topLevelExclusiveNanos = nestedExclusiveNanos = 0L;
        abandonedFrames = corruptFrames = 0L;
        STACK.remove();
        TOP_LEVEL.remove();
        active = true;
    }

    public static void profileTopLevelLoop(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> action) {
        if (!active) {
            models.forEach(action);
            return;
        }
        models.forEach((location, model) -> {
            TOP_LEVEL.set(location);
            Deque<Frame> stack = STACK.get();
            if (!stack.isEmpty()) {
                synchronized (ResidualModelBakeProfiler.class) { abandonedFrames += stack.size(); }
                stack.clear();
            }
            try {
                action.accept(location, model);
            } finally {
                if (!stack.isEmpty()) {
                    synchronized (ResidualModelBakeProfiler.class) { abandonedFrames += stack.size(); }
                    stack.clear();
                }
                TOP_LEVEL.remove();
            }
        });
    }

    public static void beginUncached(UnbakedModel model) {
        if (!active) return;
        Deque<Frame> stack = STACK.get();
        boolean topLevel = stack.isEmpty();
        ModelResourceLocation root = TOP_LEVEL.get();
        stack.push(new Frame(model, System.nanoTime(), category(model), namespace(model, root), topLevel));
        synchronized (ResidualModelBakeProfiler.class) {
            calls++;
            if (topLevel) topLevelCalls++; else nestedCalls++;
        }
    }

    public static void endUncached(UnbakedModel model) {
        if (!active) return;
        Deque<Frame> stack = STACK.get();
        Frame frame = stack.poll();
        if (frame == null || frame.model != model) {
            synchronized (ResidualModelBakeProfiler.class) {
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
        synchronized (ResidualModelBakeProfiler.class) {
            exclusiveNanos += exclusive;
            if (frame.topLevel) topLevelExclusiveNanos += exclusive; else nestedExclusiveNanos += exclusive;
            BY_CATEGORY.computeIfAbsent(frame.category, ignored -> new Stats()).add(exclusive, frame.topLevel);
            BY_NAMESPACE.computeIfAbsent(frame.namespace, ignored -> new Stats()).add(exclusive, frame.topLevel);
        }
    }

    public static synchronized void finish() {
        if (!active) return;
        active = false;
        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) {
            abandonedFrames += stack.size();
            stack.clear();
        }
        STACK.remove();
        TOP_LEVEL.remove();
        LOGGER.info("BOOTOPTIM_RESIDUAL_MODEL_BAKE calls={} top_level_calls={} nested_calls={} exclusive_ms={} top_level_exclusive_ms={} nested_exclusive_ms={} abandoned_frames={} corrupt_frames={}",
                calls, topLevelCalls, nestedCalls, ms(exclusiveNanos), ms(topLevelExclusiveNanos), ms(nestedExclusiveNanos), abandonedFrames, corruptFrames);
        logTop("category", BY_CATEGORY, 25);
        logTop("namespace", BY_NAMESPACE, 25);
    }

    private static String category(UnbakedModel model) {
        if (!(model instanceof BlockModel blockModel)) return model.getClass().getName();
        if (blockModel.customData.hasCustomGeometry()) {
            Object geometry = blockModel.customData.getCustomGeometry();
            return geometry == null ? "BlockModel/custom" : "BlockModel/custom:" + geometry.getClass().getName();
        }
        BlockModel root = blockModel.getRootModel();
        if (root == ModelBakery.GENERATION_MARKER) return "BlockModel/generated_item";
        if (root == ModelBakery.BLOCK_ENTITY_MARKER) return "BlockModel/builtin_entity";
        return "BlockModel/elements";
    }

    private static String namespace(UnbakedModel model, ModelResourceLocation topLevel) {
        if (model instanceof BlockModel blockModel) {
            String name = blockModel.name;
            int separator = name.indexOf(':');
            if (separator > 0) return name.substring(0, separator);
        }
        return topLevel == null ? "unknown" : topLevel.id().getNamespace();
    }

    private static void logTop(String dimension, Map<String, Stats> map, int limit) {
        List<Map.Entry<String, Stats>> sorted = new ArrayList<>(map.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Stats>>comparingLong(entry -> entry.getValue().nanos).reversed());
        long total = sorted.stream().mapToLong(entry -> entry.getValue().nanos).sum();
        int rank = 0;
        for (Map.Entry<String, Stats> entry : sorted) {
            if (++rank > limit) break;
            Stats stats = entry.getValue();
            LOGGER.info("BOOTOPTIM_RESIDUAL_MODEL_BAKE_TOP dimension={} rank={} key={} calls={} top_level_calls={} nested_calls={} exclusive_ms={} share_percent={} avg_us={} max_us={}",
                    dimension, rank, entry.getKey(), stats.calls, stats.topLevelCalls, stats.calls - stats.topLevelCalls,
                    ms(stats.nanos), percent(stats.nanos, total),
                    String.format(Locale.ROOT, "%.3f", stats.calls == 0 ? 0.0D : stats.nanos / 1_000.0D / stats.calls),
                    String.format(Locale.ROOT, "%.3f", stats.maxNanos / 1_000.0D));
        }
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D); }
    private static String percent(long part, long total) { return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total); }

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
        long nanos;
        long maxNanos;
        void add(long value, boolean topLevel) {
            calls++;
            if (topLevel) topLevelCalls++;
            nanos += value;
            maxNanos = Math.max(maxNanos, value);
        }
    }
}
