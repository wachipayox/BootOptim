package dev.wachipayox.bootoptim.optimization.client;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Statistics and exact-version gate for the Decocraft 3.0.11 corner-rotation experiment.
 *
 * <p>The actual reuse lives inside the BlockbenchBakery mixin so the private stock
 * applyElementRotation method remains authoritative for every first-seen corner.</p>
 */
public final class DecocraftCornerRotationReuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftCornerRotation");
    private static final String SUPPORTED_VERSION = "3.0.11";
    private static final boolean REQUESTED = Boolean.getBoolean("boot_optim.experimentalDecocraftCornerRotationReuse");
    private static final boolean PROFILE = Boolean.getBoolean("boot_optim.profileDecocraftCornerRotationReuse");

    private static final LongAdder REDIRECT_CALLS = new LongAdder();
    private static final LongAdder PREPARE_CALLS = new LongAdder();
    private static final LongAdder REUSE_CALLS = new LongAdder();
    private static final LongAdder ELEMENT_TRANSITIONS = new LongAdder();
    private static final LongAdder CLASSIFICATION_FALLBACKS = new LongAdder();
    private static volatile int supportState; // 0 unknown, 1 supported, -1 unsupported
    private static volatile String supportReason = "unchecked";

    private DecocraftCornerRotationReuse() {}

    public static boolean enabled() {
        if (!REQUESTED) return false;
        int state = supportState;
        if (state != 0) return state > 0;
        synchronized (DecocraftCornerRotationReuse.class) {
            state = supportState;
            if (state != 0) return state > 0;
            try {
                var container = ModList.get().getModContainerById("decocraft");
                if (container.isEmpty()) {
                    supportReason = "mod_missing";
                    supportState = -1;
                    return false;
                }
                String version = String.valueOf(container.get().getModInfo().getVersion());
                if (!SUPPORTED_VERSION.equals(version)) {
                    supportReason = "version_" + sanitize(version);
                    supportState = -1;
                    return false;
                }
                supportReason = "version_3.0.11";
                supportState = 1;
                return true;
            } catch (Throwable t) {
                supportReason = "version_check_failed_" + sanitize(t.getClass().getSimpleName());
                supportState = -1;
                return false;
            }
        }
    }

    public static boolean profiling() {
        return PROFILE;
    }

    public static void redirect() {
        if (PROFILE) REDIRECT_CALLS.increment();
    }

    public static void prepared() {
        if (PROFILE) PREPARE_CALLS.increment();
    }

    public static void reused() {
        if (PROFILE) REUSE_CALLS.increment();
    }

    public static void elementTransition() {
        if (PROFILE) ELEMENT_TRANSITIONS.increment();
    }

    public static void classificationFallback() {
        if (PROFILE) CLASSIFICATION_FALLBACKS.increment();
    }

    public static void finishModelBake() {
        if (!PROFILE && !REQUESTED) return;
        long redirects = REDIRECT_CALLS.sumThenReset();
        long prepares = PREPARE_CALLS.sumThenReset();
        long reuses = REUSE_CALLS.sumThenReset();
        long transitions = ELEMENT_TRANSITIONS.sumThenReset();
        long fallbacks = CLASSIFICATION_FALLBACKS.sumThenReset();
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_CORNER_ROTATION status={} reason={} redirect_calls={} prepare_calls={} commit_calls={} reused_calls={} element_transitions={} classification_fallbacks={} reuse_pct={}",
                enabled() ? "enabled" : "disabled",
                supportReason,
                redirects,
                prepares,
                redirects,
                reuses,
                transitions,
                fallbacks,
                redirects == 0L ? "0.000" : String.format(Locale.ROOT, "%.3f", reuses * 100.0D / redirects));
    }

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
