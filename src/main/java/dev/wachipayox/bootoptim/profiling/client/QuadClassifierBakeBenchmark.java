package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic only: two clocks per bake, never one clock per quad. */
public final class QuadClassifierBakeBenchmark {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileSodiumQuadBake");
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final ThreadLocal<Bake> CURRENT = new ThreadLocal<>();

    private QuadClassifierBakeBenchmark() {}

    public static void begin() {
        if (ENABLED) CURRENT.set(new Bake(GENERATION.incrementAndGet(), System.nanoTime()));
    }

    public static void eligibleQuad() {
        if (!ENABLED) return;
        Bake bake = CURRENT.get();
        if (bake != null) bake.calls++;
    }

    public static void finish() {
        if (!ENABLED) return;
        long end = System.nanoTime();
        Bake bake = CURRENT.get();
        CURRENT.remove();
        if (bake == null) return;
        LogUtils.getLogger().info("BOOTOPTIM_SODIUM_QUAD_BAKE generation={} origin=bakeModels_enter endpoint=bakeModels_return wall_ns={} eligible_calls={} candidate={}",
                bake.generation, end - bake.origin, bake.calls, Boolean.getBoolean("boot_optim.sodiumAxisQuadFlags"));
    }

    private static final class Bake {
        final int generation;
        final long origin;
        long calls;

        Bake(int generation, long origin) {
            this.generation = generation;
            this.origin = origin;
        }
    }
}
