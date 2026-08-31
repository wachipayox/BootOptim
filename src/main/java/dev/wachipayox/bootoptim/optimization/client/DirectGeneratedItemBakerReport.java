package dev.wachipayox.bootoptim.optimization.client;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Diagnostic-only report emitted after ModelBakery finishes, because CI terminates the client after menu detection. */
public final class DirectGeneratedItemBakerReport {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/GeneratedItemDirect");

    private DirectGeneratedItemBakerReport() {
    }

    public static void reportAfterModelBake() {
        try {
            Class<?> owner = DirectGeneratedItemBaker.class;
            boolean enabled = booleanField(owner, "ENABLED");
            boolean verify = booleanField(owner, "VERIFY");
            long eligibleCalls = adder(owner, "ELIGIBLE_CALLS").sum();
            long candidateNs = adder(owner, "CANDIDATE_NS").sum();
            long topologyNs = adder(owner, "TOPOLOGY_NS").sum();
            long metadataNs = adder(owner, "METADATA_NS").sum();
            long quadBakeNs = adder(owner, "QUAD_BAKE_NS").sum();
            long stockVerifyNs = adder(owner, "STOCK_VERIFY_NS").sum();
            long layers = adder(owner, "LAYERS_BAKED").sum();
            long sideSpans = adder(owner, "SIDE_SPANS").sum();
            long quads = adder(owner, "QUADS_BAKED").sum();
            long elements = adder(owner, "STOCK_EQUIVALENT_ELEMENTS").sum();
            long faces = adder(owner, "STOCK_EQUIVALENT_FACES").sum();
            long matches = adder(owner, "VERIFY_MATCHES").sum();
            long mismatches = adder(owner, "VERIFY_MISMATCHES").sum();
            long fallbacks = adder(owner, "FALLBACKS").sum();

            LOGGER.info(
                    "BOOTOPTIM_GENERATED_ITEM_DIRECT summary=model_bake_complete enabled={} verify={} eligible_calls={} candidate_ms={} topology_ms={} metadata_ms={} quad_bake_ms={} stock_verify_ms={} layers={} side_spans={} quads={} stock_equivalent_elements={} stock_equivalent_faces={} verify_matches={} verify_mismatches={} fallbacks={}",
                    enabled,
                    verify,
                    eligibleCalls,
                    millis(candidateNs),
                    millis(topologyNs),
                    millis(metadataNs),
                    millis(quadBakeNs),
                    millis(stockVerifyNs),
                    layers,
                    sideSpans,
                    quads,
                    elements,
                    faces,
                    matches,
                    mismatches,
                    fallbacks);
        } catch (ReflectiveOperationException unexpected) {
            LOGGER.warn("Unable to emit generated-item direct verifier report", unexpected);
        }
    }

    private static boolean booleanField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static LongAdder adder(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (LongAdder) field.get(null);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
