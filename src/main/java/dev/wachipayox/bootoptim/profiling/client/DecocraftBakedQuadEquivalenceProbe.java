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
    private static final AtomicLong XOR = new AtomicLong();
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
            long hash = 0xcbf29ce484222325L;
            int count = 0;
            for (Object entry : list) {
                if (!(entry instanceof BakedQuad quad)) continue;
                count++;
                for (int vertex : quad.getVertices()) hash = mix(hash, vertex);
                hash = mix(hash, quad.getTintIndex());
                hash = mix(hash, quad.getDirection().ordinal());
                hash = mix(hash, quad.isShade() ? 1 : 0);
                hash = mix(hash, String.valueOf(quad.getSprite()).hashCode());
            }
            MODELS.increment();
            QUADS.add(count);
            final long modelHash = hash;
            XOR.getAndUpdate(previous -> previous ^ modelHash);
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Decocraft baked-quad equivalence probe disabled: {}", t.toString());
        }
    }

    public static void finishModelBake() {
        if (!ENABLED) return;
        long models = MODELS.sumThenReset();
        long quads = QUADS.sumThenReset();
        long xor = XOR.getAndSet(0L);
        if (models == 0L) return;
        LOGGER.info("BOOTOPTIM_DECOCRAFT_QUAD_EQUIVALENCE models={} quads={} xor={}",
                models, quads, String.format(Locale.ROOT, "%016x", xor));
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }
}
