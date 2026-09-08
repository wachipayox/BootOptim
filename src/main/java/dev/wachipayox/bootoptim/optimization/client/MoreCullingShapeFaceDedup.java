package dev.wachipayox.bootoptim.optimization.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reload-local identity cache for MoreCulling's repeated VoxelShape face
 * derivation. It never caches across reloads and never bypasses a block's
 * occlusion-shape callback; it only reuses the six immutable face results after
 * the callback has returned the same VoxelShape instance.
 */
public final class MoreCullingShapeFaceDedup {
    private static final String PROPERTY = "boot_optim.morecullingShapeFaceDedup";
    private static final ThreadLocal<IdentityHashMap<VoxelShape, VoxelShape[]>> CACHE = new ThreadLocal<>();

    private MoreCullingShapeFaceDedup() {
    }

    public static void states(Iterable<?> states, Consumer<Object> action) {
        if (!Boolean.getBoolean(PROPERTY)) {
            states.forEach(action);
            return;
        }

        IdentityHashMap<VoxelShape, VoxelShape[]> cache = new IdentityHashMap<>();
        CACHE.set(cache);
        try {
            states.forEach(action);
            System.out.println("BOOTOPTIM_MORECULLING_SHAPE_DEDUP entries=" + cache.size());
        } finally {
            CACHE.remove();
        }
    }

    public static VoxelShape face(VoxelShape shape, Direction direction) {
        Map<VoxelShape, VoxelShape[]> cache = CACHE.get();
        if (cache == null) {
            return shape.getFaceShape(direction);
        }

        VoxelShape[] faces = cache.get(shape);
        if (faces == null) {
            faces = new VoxelShape[Direction.values().length];
            for (Direction candidate : Direction.values()) {
                faces[candidate.ordinal()] = shape.getFaceShape(candidate);
            }
            cache.put(shape, faces);
        }
        return faces[direction.ordinal()];
    }
}
