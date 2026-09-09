package dev.wachipayox.bootoptim.optimization.client;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact-version gate and bounded diagnostics for the Decocraft 3.0.11 corner-rotation experiment. */
public final class DecocraftCornerRotationReuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftCornerRotation");
    private static final String SUPPORTED_VERSION = "3.0.11";
    private static final boolean REQUESTED = Boolean.getBoolean("boot_optim.experimentalDecocraftCornerRotationReuse");
    private static final boolean VERIFY = Boolean.getBoolean("boot_optim.verifyDecocraftCornerRotationReuse");
    private static final boolean PROFILE = Boolean.getBoolean("boot_optim.profileDecocraftCornerRotationReuse");
    private static final int MAX_MISMATCH_EXAMPLES = 8;

    private static final LongAdder REDIRECT_CALLS = new LongAdder();
    private static final LongAdder PREPARE_CALLS = new LongAdder();
    private static final LongAdder REUSE_CANDIDATES = new LongAdder();
    private static final LongAdder REUSE_CALLS = new LongAdder();
    private static final LongAdder ELEMENT_TRANSITIONS = new LongAdder();
    private static final LongAdder CLASSIFICATION_FALLBACKS = new LongAdder();
    private static final LongAdder INPUT_ALIAS_FALLBACKS = new LongAdder();
    private static final LongAdder VERIFY_MATCHES = new LongAdder();
    private static final LongAdder VERIFY_MISMATCHES = new LongAdder();
    private static final AtomicInteger MISMATCH_EXAMPLES = new AtomicInteger();

    private static volatile int supportState; // 0 unknown, 1 supported, -1 unsupported
    private static volatile String supportReason = "unchecked";

    private DecocraftCornerRotationReuse() {}

    /** True only when the wrapped callsite should do diagnostic/substitution work. */
    public static boolean active() {
        return (REQUESTED || VERIFY) && supportedRuntime();
    }

    /** Verify takes precedence over substitution so verify-only can never alter final geometry. */
    public static boolean verifying() {
        return VERIFY && supportedRuntime();
    }

    public static boolean substituting() {
        return REQUESTED && !VERIFY && supportedRuntime();
    }

    public static void redirect() {
        if (PROFILE) REDIRECT_CALLS.increment();
    }

    public static void prepared() {
        if (PROFILE) PREPARE_CALLS.increment();
    }

    public static void reuseCandidate() {
        if (PROFILE) REUSE_CANDIDATES.increment();
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

    public static void inputAliasFallback() {
        if (PROFILE) INPUT_ALIAS_FALLBACKS.increment();
    }

    public static void verificationMatch() {
        if (PROFILE) VERIFY_MATCHES.increment();
    }

    public static void verificationMismatch(
            int corner,
            int inputX, int inputY, int inputZ,
            int cachedX, int cachedY, int cachedZ,
            int stockX, int stockY, int stockZ) {
        if (!PROFILE) return;
        VERIFY_MISMATCHES.increment();
        int index = MISMATCH_EXAMPLES.getAndIncrement();
        if (index >= MAX_MISMATCH_EXAMPLES) return;
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_CORNER_VERIFY_MISMATCH example={} corner={} input={}/{}/{} cached={}/{}/{} stock={}/{}/{}",
                index,
                corner,
                hex32(inputX), hex32(inputY), hex32(inputZ),
                hex32(cachedX), hex32(cachedY), hex32(cachedZ),
                hex32(stockX), hex32(stockY), hex32(stockZ));
    }

    public static void finishModelBake() {
        if (!PROFILE && !REQUESTED && !VERIFY) return;
        long redirects = REDIRECT_CALLS.sumThenReset();
        long prepares = PREPARE_CALLS.sumThenReset();
        long candidates = REUSE_CANDIDATES.sumThenReset();
        long reuses = REUSE_CALLS.sumThenReset();
        long transitions = ELEMENT_TRANSITIONS.sumThenReset();
        long classificationFallbacks = CLASSIFICATION_FALLBACKS.sumThenReset();
        long inputAliasFallbacks = INPUT_ALIAS_FALLBACKS.sumThenReset();
        long verifyMatches = VERIFY_MATCHES.sumThenReset();
        long verifyMismatches = VERIFY_MISMATCHES.sumThenReset();
        MISMATCH_EXAMPLES.set(0);

        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_CORNER_ROTATION mode={} reason={} redirect_calls={} prepare_calls={} commit_calls={} reuse_candidates={} reused_calls={} element_transitions={} classification_fallbacks={} input_alias_fallbacks={} verify_matches={} verify_mismatches={} reuse_pct={}",
                mode(),
                supportReason,
                redirects,
                prepares,
                redirects,
                candidates,
                reuses,
                transitions,
                classificationFallbacks,
                inputAliasFallbacks,
                verifyMatches,
                verifyMismatches,
                redirects == 0L ? "0.000" : String.format(Locale.ROOT, "%.3f", candidates * 100.0D / redirects));
    }

    private static String mode() {
        if (!supportedRuntime()) return "disabled";
        if (VERIFY) return "verify";
        if (REQUESTED) return "substitute";
        return "disabled";
    }

    private static boolean supportedRuntime() {
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

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String hex32(int value) {
        return String.format(Locale.ROOT, "%08x", value);
    }
}
