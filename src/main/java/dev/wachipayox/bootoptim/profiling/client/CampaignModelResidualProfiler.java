package dev.wachipayox.bootoptim.profiling.client;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coarse model-bake residual accounting for the scaling campaign.
 *
 * <p>Only whole-call scopes are timed: no per-face/per-quad hooks. This keeps overhead low enough
 * for a slow reference PC while still showing how the two important generic paths scale.</p>
 */
public final class CampaignModelResidualProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/CampaignModelResidual");
    private static final LongAdder ELEMENT_CALLS = new LongAdder();
    private static final LongAdder ELEMENT_NANOS = new LongAdder();
    private static final LongAdder DIRECT_CALLS = new LongAdder();
    private static final LongAdder DIRECT_NANOS = new LongAdder();
    private static volatile boolean active;

    private CampaignModelResidualProfiler() {}

    public static void beginModelBake() {
        ELEMENT_CALLS.reset();
        ELEMENT_NANOS.reset();
        DIRECT_CALLS.reset();
        DIRECT_NANOS.reset();
        active = true;
    }

    public static boolean active() {
        return active;
    }

    public static void recordElements(long nanos) {
        if (!active || nanos < 0L) return;
        ELEMENT_CALLS.increment();
        ELEMENT_NANOS.add(nanos);
    }

    public static void recordDirectGenerated(long nanos) {
        if (!active || nanos < 0L) return;
        DIRECT_CALLS.increment();
        DIRECT_NANOS.add(nanos);
    }

    public static void finishModelBake() {
        if (!active) return;
        active = false;
        LOGGER.info(
                "BOOTOPTIM_CAMPAIGN_MODEL_RESIDUAL elements_calls={} elements_ms={} direct_generated_calls={} direct_generated_ms={}",
                ELEMENT_CALLS.sum(),
                ms(ELEMENT_NANOS.sum()),
                DIRECT_CALLS.sum(),
                ms(DIRECT_NANOS.sum()));
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }
}
