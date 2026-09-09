package dev.wachipayox.bootoptim.profiling.client;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Non-reflective output fingerprint for control/candidate comparison of Decocraft base bakes. */
public final class DecocraftCornerQuadEquivalenceProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftCornerEquivalence");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileDecocraftCornerRotationReuse");
    private static final LongAdder MODELS = new LongAdder();
    private static final LongAdder QUADS = new LongAdder();
    private static final AtomicLong MODEL_XOR = new AtomicLong();
    private static final AtomicLong MODEL_SUM = new AtomicLong();
    private static final AtomicLong QUAD_XOR = new AtomicLong();
    private static final AtomicLong QUAD_SUM = new AtomicLong();
    private static final AtomicLong META_XOR = new AtomicLong();
    private static final AtomicLong META_SUM = new AtomicLong();

    private DecocraftCornerQuadEquivalenceProbe() {}

    public static void observe(List<BakedQuad> list) {
        if (!ENABLED || list == null) return;
        long modelQuadXor = 0L;
        long modelQuadSum = 0L;
        long modelMetaXor = 0L;
        long modelMetaSum = 0L;
        int count = 0;
        for (BakedQuad quad : list) {
            if (quad == null) continue;
            count++;
            TextureAtlasSprite sprite = quad.getSprite();
            long quadHash = 0xcbf29ce484222325L;
            int[] vertices = quad.getVertices();
            for (int i = 0; i < vertices.length; i++) {
                quadHash = mix(quadHash, logicalVertexValue(i & 7, vertices[i], sprite));
            }
            long metadata = 0xcbf29ce484222325L;
            metadata = mix(metadata, quad.getTintIndex());
            metadata = mix(metadata, quad.getDirection().ordinal());
            metadata = mix(metadata, quad.isShade() ? 1 : 0);
            metadata = mix(metadata, sprite.contents().name().hashCode());
            quadHash = mix(quadHash, metadata);
            modelQuadXor ^= quadHash;
            modelQuadSum += quadHash;
            modelMetaXor ^= metadata;
            modelMetaSum += metadata;
        }
        long modelHash = mix(mix(0xcbf29ce484222325L, count), modelQuadXor);
        modelHash = mix(modelHash, modelQuadSum);
        MODELS.increment();
        QUADS.add(count);
        xor(MODEL_XOR, modelHash);
        add(MODEL_SUM, modelHash);
        xor(QUAD_XOR, modelQuadXor);
        add(QUAD_SUM, modelQuadSum);
        xor(META_XOR, modelMetaXor);
        add(META_SUM, modelMetaSum);
    }

    public static void finishModelBake() {
        if (!ENABLED) return;
        long models = MODELS.sumThenReset();
        long quads = QUADS.sumThenReset();
        if (models == 0L) return;
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_CORNER_EQUIVALENCE uv_space=sprite_local_q1e6 models={} quads={} model_xor={} model_sum={} quad_xor={} quad_sum={} meta_xor={} meta_sum={}",
                models, quads,
                hex(MODEL_XOR.getAndSet(0L)), hex(MODEL_SUM.getAndSet(0L)),
                hex(QUAD_XOR.getAndSet(0L)), hex(QUAD_SUM.getAndSet(0L)),
                hex(META_XOR.getAndSet(0L)), hex(META_SUM.getAndSet(0L)));
    }

    private static long logicalVertexValue(int lane, int raw, TextureAtlasSprite sprite) {
        if (lane != 4 && lane != 5) return Integer.toUnsignedLong(raw);
        float atlas = Float.intBitsToFloat(raw);
        float origin = lane == 4 ? sprite.getU0() : sprite.getV0();
        float end = lane == 4 ? sprite.getU1() : sprite.getV1();
        float span = end - origin;
        if (!Float.isFinite(atlas) || !Float.isFinite(origin) || !Float.isFinite(span) || span == 0.0F) {
            return Integer.toUnsignedLong(raw);
        }
        return Math.round(((atlas - origin) / span) * 1_000_000.0F);
    }

    private static void xor(AtomicLong target, long value) {
        target.getAndUpdate(previous -> previous ^ value);
    }

    private static void add(AtomicLong target, long value) {
        target.getAndUpdate(previous -> previous + value);
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }
}
