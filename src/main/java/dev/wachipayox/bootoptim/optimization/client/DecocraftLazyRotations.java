package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defers the quad rotation of a derived Decocraft quarter-turn variant to its first use, and keeps
 * its inputs as a compact immutable snapshot ({@link FrozenQuadInputs}) instead of the source quad
 * list. Used only by {@link DecocraftRotatedQuadReuse} for the exact, already audited rotation call.
 *
 * <ul>
 *   <li>The result is {@code original(source, matrix)} computed once on the first access of any
 *       element, under a lock, from the snapshot taken at bake time (so later mutation of the
 *       base model's arrays cannot leak into the variant). It is the same eager code path.</li>
 *   <li>Only lists of at most 16,384 exact {@code BakedQuad}s with 32-int payloads are deferred;
 *       anything else runs eagerly, with its stock failure behaviour.</li>
 *   <li>Snapshots of the same source list are shared (weak index); geometry identical across
 *       texture variants is shared by {@link SplitQuadGeometry}; the palette encoding is bit exact.</li>
 *   <li>No OpenGL, no worker threads: the realization runs on whichever thread first reads the quads.</li>
 * </ul>
 *
 * <p>Default off: {@code -Dboot_optim.decocraftLazyRotations=true} enables it. The mechanism changes
 * when the rotation CPU is spent (first read instead of bake), not what it computes.</p>
 */
final class DecocraftLazyRotations {
    static final String PROPERTY = "boot_optim.decocraftLazyRotations";
    private static final String MECHANISM = "decocraft-lazy-rotations";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftLazyRotations");
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final int MAX_QUADS = 16384;

    private static final Map<List<BakedQuad>, WeakReference<FrozenQuadInputs>> SNAPSHOTS = new WeakHashMap<>();
    private static final AtomicLong PLANS = new AtomicLong();
    private static final AtomicLong PLANNED_QUADS = new AtomicLong();
    private static final AtomicLong MATERIALIZED = new AtomicLong();
    private static final AtomicLong REALIZATIONS = new AtomicLong();
    private static final AtomicLong FIRST_USE_NANOS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_BYTES = new AtomicLong();
    private static final AtomicLong MAX_FIRST_USE_NANOS = new AtomicLong();

    private DecocraftLazyRotations() {}

    static boolean enabled() {
        return ENABLED;
    }

    static List<BakedQuad> defer(
            List<BakedQuad> source,
            Matrix4f matrix,
            BiFunction<List<BakedQuad>, Matrix4f, List<BakedQuad>> original) {
        // Unknown quad implementations keep the eager stock path, including its null/failure behaviour.
        if (!ENABLED || source.size() > MAX_QUADS) {
            return original.apply(source, matrix);
        }
        for (BakedQuad quad : source) {
            if (quad == null || quad.getClass() != BakedQuad.class || quad.getVertices().length != 32) {
                return original.apply(source, matrix);
            }
        }
        FrozenQuadInputs frozen;
        synchronized (SNAPSHOTS) {
            WeakReference<FrozenQuadInputs> weak = SNAPSHOTS.get(source);
            frozen = weak == null ? null : weak.get();
            if (frozen == null || !frozen.matches(source)) {
                frozen = new FrozenQuadInputs(source);
                SNAPSHOT_BYTES.addAndGet(frozen.vertexPayload());
                SNAPSHOTS.put(source, new WeakReference<>(frozen));
            }
        }
        FrozenQuadInputs input = frozen;
        Matrix4f rotation = new Matrix4f(matrix);
        int size = source.size();
        long plan = PLANS.incrementAndGet();
        PLANNED_QUADS.addAndGet(size);
        if (plan == 1) {
            CompatLog.active(MECHANISM, "decocraft");
        }
        if (plan == 1 || plan % 3000 == 0) {
            LOGGER.info("BOOTOPTIM_DECOCRAFT_LAZY_ROTATIONS {}", status());
        }
        return new DeferredImmutableList<>(size, () -> {
            long start = System.nanoTime();
            List<BakedQuad> result = original.apply(input.readView(), rotation);
            if (result == null) {
                CompatLog.fallback(MECHANISM, "decocraft", "unexpected_null_rotation");
                throw new IllegalStateException("validated_rotation_returned_null");
            }
            MATERIALIZED.addAndGet(size);
            long elapsed = System.nanoTime() - start;
            FIRST_USE_NANOS.addAndGet(elapsed);
            MAX_FIRST_USE_NANOS.accumulateAndGet(elapsed, Math::max);
            long realized = REALIZATIONS.incrementAndGet();
            if (realized == 1 || realized % 250 == 0) {
                LOGGER.info("BOOTOPTIM_DECOCRAFT_LAZY_ROTATIONS first_use {}", status());
            }
            return result;
        });
    }

    static String status() {
        return "lazy_rotation_plans=" + PLANS.get() + " planned_quads=" + PLANNED_QUADS.get()
                + " materialized_quads=" + MATERIALIZED.get() + " cumulative_snapshot_payload=" + SNAPSHOT_BYTES.get()
                + " realized_variants=" + REALIZATIONS.get() + " total_first_use_ns=" + FIRST_USE_NANOS.get()
                + " max_first_use_ns=" + MAX_FIRST_USE_NANOS.get() + " " + SplitQuadGeometry.status()
                + " " + PalettedIntChunk.status();
    }
}
