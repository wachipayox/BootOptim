package dev.wachipayox.bootoptim.profiling.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
    private static final AtomicLong QUAD_XOR = new AtomicLong();
    private static final AtomicLong QUAD_SUM = new AtomicLong();
    private static final AtomicLongArray LANE_XOR = new AtomicLongArray(8);
    private static final AtomicLongArray LANE_SUM = new AtomicLongArray(8);
    private static final AtomicLongArray LANE_QUANTIZED_SUM = new AtomicLongArray(8);
    private static final AtomicLong META_XOR = new AtomicLong();
    private static final AtomicLong META_SUM = new AtomicLong();
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
            long modelQuadXor = 0L;
            long modelQuadSum = 0L;
            long modelMetaXor = 0L;
            long modelMetaSum = 0L;
            long[] laneXor = new long[8];
            long[] laneSum = new long[8];
            long[] quantizedSum = new long[8];
            int count = 0;
            for (Object entry : list) {
                if (!(entry instanceof BakedQuad quad)) continue;
                count++;
                long quadHash = 0xcbf29ce484222325L;
                int[] vertices = quad.getVertices();
                TextureAtlasSprite sprite = quad.getSprite();
                for (int i = 0; i < vertices.length; i++) {
                    int vertex = vertices[i];
                    int lane = i & 7;
                    long logicalValue = logicalVertexValue(lane, vertex, sprite);
                    quadHash = mix(quadHash, logicalValue);

                    // Keep raw atlas-space lanes separately to diagnose per-process atlas placement.
                    laneXor[lane] ^= Integer.toUnsignedLong(vertex);
                    laneSum[lane] += Integer.toUnsignedLong(vertex);
                    if (lane == 0 || lane == 1 || lane == 2 || lane == 4 || lane == 5) {
                        float component = Float.intBitsToFloat(vertex);
                        quantizedSum[lane] += Math.round(component * 1_000_000.0F);
                    }
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
            final long finalModelHash = modelHash;
            final long finalQuadXor = modelQuadXor;
            final long finalQuadSum = modelQuadSum;
            final long finalMetaXor = modelMetaXor;
            final long finalMetaSum = modelMetaSum;
            MODEL_XOR.getAndUpdate(previous -> previous ^ finalModelHash);
            MODEL_SUM.getAndUpdate(previous -> previous + finalModelHash);
            QUAD_XOR.getAndUpdate(previous -> previous ^ finalQuadXor);
            QUAD_SUM.getAndUpdate(previous -> previous + finalQuadSum);
            META_XOR.getAndUpdate(previous -> previous ^ finalMetaXor);
            META_SUM.getAndUpdate(previous -> previous + finalMetaSum);
            for (int lane = 0; lane < 8; lane++) {
                final int index = lane;
                final long xor = laneXor[lane];
                final long sum = laneSum[lane];
                final long quantized = quantizedSum[lane];
                LANE_XOR.getAndUpdate(index, previous -> previous ^ xor);
                LANE_SUM.getAndUpdate(index, previous -> previous + sum);
                LANE_QUANTIZED_SUM.getAndUpdate(index, previous -> previous + quantized);
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Decocraft baked-quad equivalence probe disabled: {}", t.toString());
        }
    }

    public static void finishModelBake() {
        if (!ENABLED) return;
        long models = MODELS.sumThenReset();
        long quads = QUADS.sumThenReset();
        long modelXor = MODEL_XOR.getAndSet(0L);
        long modelSum = MODEL_SUM.getAndSet(0L);
        long quadXor = QUAD_XOR.getAndSet(0L);
        long quadSum = QUAD_SUM.getAndSet(0L);
        long metaXor = META_XOR.getAndSet(0L);
        long metaSum = META_SUM.getAndSet(0L);
        if (models == 0L) return;
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_QUAD_EQUIVALENCE uv_space=sprite_local_q1e6 models={} quads={} model_xor={} model_sum={} quad_xor={} quad_sum={} meta_xor={} meta_sum={}",
                models, quads,
                hex(modelXor), hex(modelSum), hex(quadXor), hex(quadSum), hex(metaXor), hex(metaSum));
        for (int lane = 0; lane < 8; lane++) {
            long xor = LANE_XOR.getAndSet(lane, 0L);
            long sum = LANE_SUM.getAndSet(lane, 0L);
            long quantized = LANE_QUANTIZED_SUM.getAndSet(lane, 0L);
            LOGGER.info(
                    "BOOTOPTIM_DECOCRAFT_QUAD_LANE lane={} raw_xor={} raw_sum={} raw_quantized_sum={}",
                    lane, hex(xor), hex(sum), quantized);
        }
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
        float local = (atlas - origin) / span;
        return Math.round(local * 1_000_000.0F);
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }
}
