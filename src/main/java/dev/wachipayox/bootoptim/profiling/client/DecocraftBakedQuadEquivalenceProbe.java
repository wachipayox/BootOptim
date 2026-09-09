package dev.wachipayox.bootoptim.profiling.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Output-only probe used to compare control/candidate Decocraft baked representations. */
public final class DecocraftBakedQuadEquivalenceProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftQuadEquivalence");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileDecocraftPreparedGeometry");
    private static final LongAdder MODELS = new LongAdder();
    private static final LongAdder QUADS = new LongAdder();
    private static final AtomicLong MODEL_XOR = new AtomicLong();
    private static final AtomicLong MODEL_SUM = new AtomicLong();
    private static volatile Field bakedQuadsField;
    private static volatile boolean failed;

    private DecocraftBakedQuadEquivalenceProbe() {}

    public static void observe(Object model) {
        if (!ENABLED || failed) return;
        try {
            Field field = bakedQuadsField;
            if (field == null) {
                field = model.getClass().getDeclaredField("bakedQuads");
                field.setAccessible(true);
                bakedQuadsField = field;
            }
            Object value = field.get(model);
            if (!(value instanceof List<?> list)) return;
            long quadXor = 0L;
            long quadSum = 0L;
            int count = 0;
            for (Object entry : list) {
                if (!(entry instanceof BakedQuad quad)) continue;
                count++;
                long quadHash = 0xcbf29ce484222325L;
                for (int vertex : quad.getVertices()) quadHash = mix(quadHash, vertex);
                quadHash = mix(quadHash, quad.getTintIndex());
                quadHash = mix(quadHash, quad.getDirection().ordinal());
                quadHash = mix(quadHash, quad.isShade() ? 1 : 0);
                quadHash = mix(quadHash, quad.getSprite().contents().name().hashCode());
                quadXor ^= quadHash;
                quadSum += quadHash;
            }
            long modelHash = mix(mix(0xcbf29ce484222325L, count), quadXor);
            modelHash = mix(modelHash, quadSum);
            MODELS.increment();
            QUADS.add(count);
            final long finalModelHash = modelHash;
            MODEL_XOR.getAndUpdate(previous -> previous ^ finalModelHash);
            MODEL_SUM.getAndUpdate(previous -> previous + finalModelHash);
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Decocraft baked-quad equivalence probe disabled: {}", t.toString());
        }
    }

    public static void finishModelBake() {
        if (!ENABLED) return;
        long models = MODELS.sumThenReset();
        long quads = QUADS.sumThenReset();
        long xor = MODEL_XOR.getAndSet(0L);
        long sum = MODEL_SUM.getAndSet(0L);
        if (models == 0L) return;
        LOGGER.info("BOOTOPTIM_DECOCRAFT_QUAD_EQUIVALENCE models={} quads={} xor={} sum={}",
                models, quads,
                String.format(Locale.ROOT, "%016x", xor),
                String.format(Locale.ROOT, "%016x", sum));
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }
}
