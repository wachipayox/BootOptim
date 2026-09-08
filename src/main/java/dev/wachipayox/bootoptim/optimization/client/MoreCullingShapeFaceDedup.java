package dev.wachipayox.bootoptim.optimization.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup-local identity cache for MoreCulling's repeated VoxelShape face
 * derivation. The cache is deliberately scoped to the candidate run: it is
 * enabled before client reload listeners run and disabled at the first title
 * screen. It never bypasses a block's occlusion-shape callback; it only
 * reuses the result of the same pure VoxelShape#getFaceShape call.
 */
public final class MoreCullingShapeFaceDedup {
    private static final String PROPERTY = "boot_optim.morecullingShapeFaceDedup";
    private static final ThreadLocal<IdentityHashMap<VoxelShape, VoxelShape[]>> CACHE = new ThreadLocal<>();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final ThreadLocal<Counters> COUNTERS = ThreadLocal.withInitial(Counters::new);

    private MoreCullingShapeFaceDedup() {
    }

    public static void beginStartup() {
        if (Boolean.getBoolean(PROPERTY)) {
            ACTIVE.set(true);
        }
    }

    public static void endStartup() {
        if (!ACTIVE.compareAndSet(true, false)) {
            return;
        }
        Counters counters = COUNTERS.get();
        IdentityHashMap<VoxelShape, VoxelShape[]> cache = CACHE.get();
        int entries = cache == null ? 0 : cache.size();
        System.out.println("BOOTOPTIM_MORECULLING_SHAPE_DEDUP entries=" + entries
                + " requested_faces=" + counters.requestedFaces
                + " computed_faces=" + counters.computedFaces
                + " reuse_hits=" + counters.reuseHits);
        CACHE.remove();
        COUNTERS.remove();
    }

    public static VoxelShape cached(VoxelShape shape, Direction direction) {
        if (!ACTIVE.get()) {
            return null;
        }
        Counters counters = COUNTERS.get();
        counters.requestedFaces++;
        IdentityHashMap<VoxelShape, VoxelShape[]> cache = CACHE.get();
        if (cache == null) {
            cache = new IdentityHashMap<>();
            CACHE.set(cache);
        }
        VoxelShape[] faces = cache.get(shape);
        if (faces == null) {
            return null;
        }
        VoxelShape cached = faces[direction.ordinal()];
        if (cached != null) {
            counters.reuseHits++;
        }
        return cached;
    }

    public static void record(VoxelShape shape, Direction direction, VoxelShape result) {
        if (!ACTIVE.get()) {
            return;
        }
        Counters counters = COUNTERS.get();
        counters.computedFaces++;
        IdentityHashMap<VoxelShape, VoxelShape[]> cache = CACHE.get();
        if (cache == null) {
            cache = new IdentityHashMap<>();
            CACHE.set(cache);
        }
        VoxelShape[] faces = cache.get(shape);
        if (faces == null) {
            faces = new VoxelShape[Direction.values().length];
            cache.put(shape, faces);
        }
        faces[direction.ordinal()] = result;
    }

    private static final class Counters {
        private int requestedFaces;
        private int computedFaces;
        private int reuseHits;
    }
}
