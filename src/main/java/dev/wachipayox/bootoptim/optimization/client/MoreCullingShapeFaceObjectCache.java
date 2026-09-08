package dev.wachipayox.bootoptim.optimization.client;

import java.util.concurrent.atomic.AtomicBoolean;

/** Diagnostic lifecycle for the lower-overhead per-VoxelShape face cache. */
public final class MoreCullingShapeFaceObjectCache {
    public static final String PROPERTY = "boot_optim.morecullingShapeFaceObjectCache";
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();

    private MoreCullingShapeFaceObjectCache() {
    }

    public static void beginStartup() {
        if (Boolean.getBoolean(PROPERTY)) {
            ACTIVE.set(true);
            System.out.println("BOOTOPTIM_MORECULLING_SHAPE_OBJECT_CACHE mode=per_shape_field active=true");
        }
    }

    public static void endStartup() {
        if (ACTIVE.compareAndSet(true, false)) {
            System.out.println("BOOTOPTIM_MORECULLING_SHAPE_OBJECT_CACHE mode=per_shape_field active=false");
        }
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}
