package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic experiment: memoize material resolution only for the duration of one vanilla model bake.
 *
 * <p>This deliberately does not retain anything across models, top-level bake calls or resource reloads. The goal is
 * to test the exact-pack cost of repeatedly calling 1.21.1 BlockModel#getMaterial for faces which reuse the same
 * texture reference inside one bake, while avoiding the invalidation/compatibility risks of a persistent cache.</p>
 */
public final class ShortScopeMaterialCacheExperiment {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ShortScopeMaterialCache");
    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private static volatile boolean active;
    private static long scopes;
    private static long elementScopes;
    private static long vanillaScopes;
    private static long materialCalls;
    private static long materialHits;
    private static long materialMisses;
    private static long maxEntries;
    private static long corruptFrames;

    private ShortScopeMaterialCacheExperiment() {}

    public static synchronized void beginExperiment() {
        scopes = elementScopes = vanillaScopes = 0L;
        materialCalls = materialHits = materialMisses = 0L;
        maxEntries = corruptFrames = 0L;
        FRAMES.remove();
        active = true;
    }

    public static void beginElements() {
        beginScope(Kind.ELEMENTS);
    }

    public static void beginVanilla() {
        beginScope(Kind.VANILLA);
    }

    private static void beginScope(Kind kind) {
        if (!active) return;
        FRAMES.get().push(new Frame(kind));
    }

    public static void endScope() {
        if (!active) return;
        Frame frame = FRAMES.get().poll();
        if (frame == null) {
            corrupt();
            return;
        }
        synchronized (ShortScopeMaterialCacheExperiment.class) {
            scopes++;
            if (frame.kind == Kind.ELEMENTS) elementScopes++;
            else vanillaScopes++;
            maxEntries = Math.max(maxEntries, frame.materials.size());
        }
    }

    public static Material resolve(IGeometryBakingContext context, String name) {
        if (!active) return context.getMaterial(name);
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.kind != Kind.ELEMENTS) return context.getMaterial(name);
        return resolve(frame, name, () -> context.getMaterial(name));
    }

    public static Material resolve(BlockModel model, String name) {
        if (!active) return model.getMaterial(name);
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.kind != Kind.VANILLA) return model.getMaterial(name);
        return resolve(frame, name, () -> model.getMaterial(name));
    }

    private static Material resolve(Frame frame, String name, MaterialResolver resolver) {
        materialCalls++;
        Material material = frame.materials.get(name);
        if (material != null) {
            materialHits++;
            return material;
        }

        materialMisses++;
        material = resolver.resolve();
        frame.materials.put(name, material);
        return material;
    }

    public static synchronized void finishExperiment() {
        if (!active) return;
        active = false;
        LOGGER.info(
                "BOOTOPTIM_SHORT_SCOPE_MATERIAL_CACHE status=experimental scopes={} element_scopes={} vanilla_scopes={} material_calls={} material_hits={} material_misses={} hit_percent={} max_entries_per_scope={} corrupt_frames={}",
                scopes,
                elementScopes,
                vanillaScopes,
                materialCalls,
                materialHits,
                materialMisses,
                percent(materialHits, materialCalls),
                maxEntries,
                corruptFrames);
        FRAMES.remove();
    }

    private static synchronized void corrupt() {
        corruptFrames++;
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }

    private enum Kind { ELEMENTS, VANILLA }

    private static final class Frame {
        final Kind kind;
        final Map<String, Material> materials = new HashMap<>();

        Frame(Kind kind) {
            this.kind = kind;
        }
    }

    @FunctionalInterface
    private interface MaterialResolver {
        Material resolve();
    }
}
