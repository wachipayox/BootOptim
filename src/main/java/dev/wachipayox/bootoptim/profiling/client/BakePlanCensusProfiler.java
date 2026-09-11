package dev.wachipayox.bootoptim.profiling.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only census for the constrained two-phase BakePlan premise from PR #261.
 *
 * <p>No model result is cached or replaced. The only retained state is census metadata: actual 1.21.1
 * semantic bake keys, observed parent/child closure classification and aggregate timings. All stock bake
 * calls still execute synchronously and in their original order.</p>
 */
public final class BakePlanCensusProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/BakePlanCensus");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileBakePlanCensus");
    private static final ThreadLocal<Deque<Frame>> BAKE_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<KeyFrame>> KEY_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<RootFrame> ROOT = new ThreadLocal<>();
    private static final Map<SemanticKey, Classification> KEY_CLASSIFICATION = new HashMap<>();
    private static final Map<String, Stats> BY_REASON = new HashMap<>();
    private static final Map<String, Stats> BY_CLASS = new HashMap<>();
    private static final Map<String, Stats> BY_KEY = new HashMap<>();

    private static volatile boolean active;
    private static long calls;
    private static long safeCalls;
    private static long opaqueCalls;
    private static long safeExclusiveNanos;
    private static long opaqueExclusiveNanos;
    private static long roots;
    private static long safeRoots;
    private static long opaqueRoots;
    private static long recursiveKeys;
    private static long cacheHitKeys;
    private static long unknownHitKeys;
    private static long corruptFrames;

    private BakePlanCensusProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static synchronized void beginBakeModels() {
        if (!ENABLED || active) return;
        KEY_CLASSIFICATION.clear();
        BY_REASON.clear();
        BY_CLASS.clear();
        BY_KEY.clear();
        calls = safeCalls = opaqueCalls = 0L;
        safeExclusiveNanos = opaqueExclusiveNanos = 0L;
        roots = safeRoots = opaqueRoots = 0L;
        recursiveKeys = cacheHitKeys = unknownHitKeys = corruptFrames = 0L;
        BAKE_STACK.remove();
        KEY_STACK.remove();
        ROOT.remove();
        active = true;
    }

    public static void beginRoot(ModelResourceLocation location) {
        if (!active) return;
        ROOT.set(new RootFrame("root:" + location));
    }

    public static void endRoot() {
        if (!active) return;
        RootFrame root = ROOT.get();
        ROOT.remove();
        if (root == null) return;
        synchronized (BakePlanCensusProfiler.class) {
            roots++;
            Classification classification = root.classification == null
                    ? Classification.opaque("warning_effect_unproven")
                    : root.classification;
            if (classification.safe) safeRoots++; else opaqueRoots++;
            Stats stats = BY_KEY.computeIfAbsent(root.key, ignored -> new Stats());
            stats.add(root.exclusiveNanos, classification.safe);
            for (String reason : classification.reasons) {
                BY_REASON.computeIfAbsent(reason, ignored -> new Stats()).add(root.exclusiveNanos, false);
            }
        }
    }

    public static void beginKey(ResourceLocation location, ModelState state) {
        if (!active) return;
        SemanticKey key = new SemanticKey(location, state.getRotation(), state.isUvLocked());
        KEY_STACK.get().push(new KeyFrame(key));
        synchronized (BakePlanCensusProfiler.class) {
            recursiveKeys++;
        }
    }

    public static void endKey() {
        if (!active) return;
        Deque<KeyFrame> stack = KEY_STACK.get();
        KeyFrame keyFrame = stack.poll();
        if (keyFrame == null) {
            synchronized (BakePlanCensusProfiler.class) {
                corruptFrames++;
            }
            return;
        }
        if (!keyFrame.uncachedSeen) {
            Classification classification;
            synchronized (BakePlanCensusProfiler.class) {
                cacheHitKeys++;
                classification = KEY_CLASSIFICATION.get(keyFrame.key);
                if (classification == null) {
                    unknownHitKeys++;
                    classification = Classification.opaque("dynamic_lookup_unknown_cache_hit");
                }
            }
            Frame parent = BAKE_STACK.get().peek();
            if (parent != null) parent.mergeChild(classification);
        }
    }

    public static void beginUncached(UnbakedModel model) {
        if (!active) return;
        Classification local = classifyLocal(model);
        Deque<KeyFrame> keyStack = KEY_STACK.get();
        KeyFrame keyFrame = keyStack.peek();
        if (keyFrame != null) keyFrame.uncachedSeen = true;
        BAKE_STACK.get().push(new Frame(model, keyFrame == null ? null : keyFrame.key, local, System.nanoTime()));
    }

    public static void endUncached(UnbakedModel model) {
        if (!active) return;
        Deque<Frame> stack = BAKE_STACK.get();
        Frame frame = stack.poll();
        if (frame == null || frame.model != model) {
            synchronized (BakePlanCensusProfiler.class) {
                corruptFrames++;
            }
            stack.clear();
            return;
        }

        long elapsed = System.nanoTime() - frame.startedNanos;
        long exclusive = Math.max(0L, elapsed - frame.childNanos);
        Classification closure = frame.classification();
        Frame parent = stack.peek();
        if (parent != null) {
            parent.childNanos += elapsed;
            parent.mergeChild(closure);
        } else {
            RootFrame root = ROOT.get();
            if (root != null) {
                root.classification = root.classification == null ? closure : root.classification.merge(closure);
                root.exclusiveNanos += exclusive;
            }
        }

        synchronized (BakePlanCensusProfiler.class) {
            calls++;
            if (closure.safe) {
                safeCalls++;
                safeExclusiveNanos += exclusive;
            } else {
                opaqueCalls++;
                opaqueExclusiveNanos += exclusive;
            }
            BY_CLASS.computeIfAbsent(model.getClass().getName(), ignored -> new Stats()).add(exclusive, closure.safe);
            for (String reason : closure.reasons) {
                BY_REASON.computeIfAbsent(reason, ignored -> new Stats()).add(exclusive, false);
            }
            if (frame.key != null) {
                Classification previous = KEY_CLASSIFICATION.putIfAbsent(frame.key, closure);
                if (previous != null && !previous.equals(closure)) {
                    Classification unstable = previous.merge(closure).merge(Classification.opaque("semantic_key_unstable"));
                    KEY_CLASSIFICATION.put(frame.key, unstable);
                }
                BY_KEY.computeIfAbsent(frame.key.display(), ignored -> new Stats()).add(exclusive, closure.safe);
            }
        }
    }

    public static synchronized void finishBakeModels() {
        if (!active) return;
        active = false;
        LOGGER.info(
                "BOOTOPTIM_BAKEPLAN_CENSUS kind=summary calls={} safe_calls={} opaque_calls={} safe_exclusive_ms={} opaque_exclusive_ms={} safe_share_percent={} roots={} safe_roots={} opaque_roots={} semantic_keys={} recursive_key_calls={} cache_hit_key_calls={} unknown_hit_keys={} corrupt_frames={}",
                calls, safeCalls, opaqueCalls, ms(safeExclusiveNanos), ms(opaqueExclusiveNanos),
                percent(safeExclusiveNanos, safeExclusiveNanos + opaqueExclusiveNanos), roots, safeRoots, opaqueRoots,
                KEY_CLASSIFICATION.size(), recursiveKeys, cacheHitKeys, unknownHitKeys, corruptFrames);
        logTop("reason", BY_REASON, 20);
        logTop("class", BY_CLASS, 20);
        logTop("semantic_key", BY_KEY, 20);
        BAKE_STACK.remove();
        KEY_STACK.remove();
        ROOT.remove();
    }

    private static Classification classifyLocal(UnbakedModel model) {
        Class<?> type = model.getClass();
        if (type == MultiVariant.class || type == MultiPart.class) {
            return Classification.safe();
        }
        if (model instanceof BlockModel blockModel) {
            if (type != BlockModel.class) return Classification.opaque("extension_model_class");
            if (blockModel.customData.hasCustomGeometry()) return Classification.opaque("custom_geometry_loader");
            BlockModel root = blockModel.getRootModel();
            if (root == ModelBakery.GENERATION_MARKER) return Classification.opaque("dynamic_generated_item");
            if (root == ModelBakery.BLOCK_ENTITY_MARKER) return Classification.opaque("builtin_entity_extension");
            // Ordinary 1.21.1 BlockModel baking still crosses the supplied texture getter and NeoForge baking
            // context. This census deliberately does not wrap either surface, so warning/effect absence cannot be
            // demonstrated merely from the runtime class. Under #261's strict gate the leaf remains opaque.
            return Classification.opaque("warning_effect_unproven");
        }
        return Classification.opaque("custom_or_unknown_unbaked_model");
    }

    private static void logTop(String dimension, Map<String, Stats> map, int limit) {
        List<Map.Entry<String, Stats>> rows = new ArrayList<>(map.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Stats>>comparingLong(entry -> entry.getValue().nanos).reversed());
        int rank = 0;
        for (Map.Entry<String, Stats> entry : rows) {
            if (++rank > limit) break;
            Stats stats = entry.getValue();
            LOGGER.info(
                    "BOOTOPTIM_BAKEPLAN_CENSUS kind=top dimension={} rank={} key={} calls={} safe_calls={} opaque_calls={} exclusive_ms={} safe_exclusive_ms={} opaque_exclusive_ms={}",
                    dimension, rank, sanitize(entry.getKey()), stats.calls, stats.safeCalls,
                    stats.calls - stats.safeCalls, ms(stats.nanos), ms(stats.safeNanos), ms(stats.nanos - stats.safeNanos));
        }
    }

    private static String sanitize(String value) {
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }

    private record SemanticKey(ResourceLocation location, Object rotation, boolean uvLocked) {
        String display() {
            return location + "|rotation_hash=" + Integer.toUnsignedString(rotation.hashCode()) + "|uv=" + uvLocked;
        }
    }

    private static final class KeyFrame {
        final SemanticKey key;
        boolean uncachedSeen;

        KeyFrame(SemanticKey key) {
            this.key = key;
        }
    }

    private static final class Frame {
        final UnbakedModel model;
        final SemanticKey key;
        final Classification local;
        final long startedNanos;
        long childNanos;
        Classification children = Classification.safe();

        Frame(UnbakedModel model, SemanticKey key, Classification local, long startedNanos) {
            this.model = model;
            this.key = key;
            this.local = local;
            this.startedNanos = startedNanos;
        }

        void mergeChild(Classification child) {
            children = children.merge(child);
        }

        Classification classification() {
            return local.merge(children);
        }
    }

    private static final class RootFrame {
        final String key;
        Classification classification;
        long exclusiveNanos;

        RootFrame(String key) {
            this.key = key;
        }
    }

    private static final class Classification {
        final boolean safe;
        final List<String> reasons;

        Classification(boolean safe, List<String> reasons) {
            this.safe = safe;
            this.reasons = reasons;
        }

        static Classification safe() {
            return new Classification(true, List.of());
        }

        static Classification opaque(String reason) {
            return new Classification(false, List.of(reason));
        }

        Classification merge(Classification other) {
            if (this.safe && other.safe) return safe();
            ArrayList<String> merged = new ArrayList<>(this.reasons.size() + other.reasons.size());
            merged.addAll(this.reasons);
            for (String reason : other.reasons) if (!merged.contains(reason)) merged.add(reason);
            return new Classification(false, List.copyOf(merged));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Classification classification
                    && safe == classification.safe
                    && reasons.equals(classification.reasons);
        }

        @Override
        public int hashCode() {
            return 31 * Boolean.hashCode(safe) + reasons.hashCode();
        }
    }

    private static final class Stats {
        long calls;
        long safeCalls;
        long nanos;
        long safeNanos;

        void add(long value, boolean safe) {
            calls++;
            nanos += value;
            if (safe) {
                safeCalls++;
                safeNanos += value;
            }
        }
    }
}
