package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.client.resources.model.Material;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;

/**
 * Diagnostic experiment: memoize material resolution only for the duration of one ElementsModel bake.
 *
 * <p>Scopes are reused per thread and use a tiny linear cache because real vanilla models normally expose only a
 * handful of texture keys. This avoids allocating a HashMap for every model and keeps the experiment overhead small
 * enough that exact-pack wall-time comparison is meaningful. Caching is restricted to NeoForge's stock
 * BlockGeometryBakingContext, whose getMaterial implementation delegates directly to its immutable-for-bake
 * BlockModel owner; arbitrary modded IGeometryBakingContext implementations keep stock per-call semantics.</p>
 */
public final class ShortScopeMaterialCacheExperiment {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ShortScopeMaterialCache");
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private static volatile boolean active;
    private static long scopes;
    private static long elementScopes;
    private static long materialCalls;
    private static long materialHits;
    private static long materialMisses;
    private static long maxEntries;
    private static long generatedModels;
    private static long generatedMaterialCalls;
    private static long generatedMaterialHits;
    private static long generatedMaterialMisses;
    private static long generatedMaxEntries;
    private static long corruptFrames;

    private ShortScopeMaterialCacheExperiment() {}

    public static synchronized void beginExperiment() {
        scopes = elementScopes = 0L;
        materialCalls = materialHits = materialMisses = 0L;
        maxEntries = 0L;
        generatedModels = generatedMaterialCalls = generatedMaterialHits = generatedMaterialMisses = generatedMaxEntries = 0L;
        corruptFrames = 0L;
        STATE.remove();
        active = true;
    }

    public static void beginElements() {
        if (!active) return;
        STATE.get().push();
    }

    public static void endScope() {
        if (!active) return;
        State state = STATE.get();
        Frame frame = state.pop();
        if (frame == null) {
            corrupt();
            return;
        }
        synchronized (ShortScopeMaterialCacheExperiment.class) {
            scopes++;
            elementScopes++;
            maxEntries = Math.max(maxEntries, frame.size);
        }
    }

    public static Material resolve(IGeometryBakingContext context, String name) {
        if (!active || !(context instanceof BlockGeometryBakingContext)) return context.getMaterial(name);
        Frame frame = STATE.get().peek();
        if (frame == null) return context.getMaterial(name);

        materialCalls++;
        Material cached = frame.get(name);
        if (cached != null) {
            materialHits++;
            return cached;
        }

        materialMisses++;
        Material resolved = context.getMaterial(name);
        frame.put(name, resolved);
        return resolved;
    }

    public static void recordGeneratedModel() {
        if (active) generatedModels++;
    }

    public static void recordGeneratedMaterialCall(boolean hit) {
        if (!active) return;
        generatedMaterialCalls++;
        if (hit) generatedMaterialHits++;
        else generatedMaterialMisses++;
    }

    public static void recordGeneratedEntries(int entries) {
        if (active && entries > generatedMaxEntries) generatedMaxEntries = entries;
    }

    public static synchronized void finishExperiment() {
        if (!active) return;
        active = false;
        LOGGER.info(
                "BOOTOPTIM_SHORT_SCOPE_MATERIAL_CACHE status=experimental scopes={} element_scopes={} material_calls={} material_hits={} material_misses={} hit_percent={} max_entries_per_scope={} generated_models={} generated_material_calls={} generated_material_hits={} generated_material_misses={} generated_hit_percent={} generated_max_entries={} corrupt_frames={}",
                scopes,
                elementScopes,
                materialCalls,
                materialHits,
                materialMisses,
                percent(materialHits, materialCalls),
                maxEntries,
                generatedModels,
                generatedMaterialCalls,
                generatedMaterialHits,
                generatedMaterialMisses,
                percent(generatedMaterialHits, generatedMaterialCalls),
                generatedMaxEntries,
                corruptFrames);
        STATE.remove();
    }

    private static synchronized void corrupt() {
        corruptFrames++;
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }

    private static final class State {
        private Frame[] frames = {new Frame(), new Frame()};
        private int depth;

        void push() {
            if (depth == frames.length) frames = Arrays.copyOf(frames, frames.length * 2);
            if (frames[depth] == null) frames[depth] = new Frame();
            frames[depth++].clear();
        }

        Frame peek() {
            return depth == 0 ? null : frames[depth - 1];
        }

        Frame pop() {
            return depth == 0 ? null : frames[--depth];
        }
    }

    private static final class Frame {
        private String[] names = new String[8];
        private Material[] materials = new Material[8];
        private int size;

        void clear() {
            for (int i = 0; i < size; i++) {
                names[i] = null;
                materials[i] = null;
            }
            size = 0;
        }

        Material get(String name) {
            for (int i = 0; i < size; i++) {
                if (names[i].equals(name)) return materials[i];
            }
            return null;
        }

        void put(String name, Material material) {
            if (size == names.length) {
                names = Arrays.copyOf(names, names.length * 2);
                materials = Arrays.copyOf(materials, materials.length * 2);
            }
            names[size] = name;
            materials[size] = material;
            size++;
        }
    }
}
