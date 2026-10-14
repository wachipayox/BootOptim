package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Differential guard for the Sodium face-axis classifier. Not pinned to a Sodium version: the
 * first {@value #WARMUP_SAMPLES} eligible calls and then one in {@value #SAMPLE_INTERVAL} keep
 * Sodium's own result and compare it with BootOptim's. Any mismatch permanently returns to stock
 * and logs {@code [WBOMBO-COMPAT] FALLBACK}, so a changed Sodium algorithm cannot be silently
 * replaced.
 */
public final class SodiumQuadFlagSupport {
    static final String MECHANISM = "sodium-axis-quad-flags";
    private static final int WARMUP_SAMPLES = 4_096;
    private static final long SAMPLE_INTERVAL = 1_024L;
    private static final AtomicInteger VERIFIED = new AtomicInteger();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final ThreadLocal<Boolean> VERIFYING = ThreadLocal.withInitial(() -> false);
    private static volatile boolean disabled;

    private SodiumQuadFlagSupport() {}

    /** At method HEAD: true to replace this call; false to let Sodium compute it (maybe verified). */
    public static boolean replaceThisCall() {
        if (disabled) {
            return false;
        }
        boolean verify = VERIFIED.get() < WARMUP_SAMPLES || CALLS.incrementAndGet() % SAMPLE_INTERVAL == 0;
        VERIFYING.set(verify);
        return !verify;
    }

    /** At method RETURN of a call Sodium computed itself. */
    public static void verify(int stockFlags, int candidateFlags, int face) {
        if (!VERIFYING.get()) {
            return;
        }
        VERIFYING.set(false);
        if (stockFlags != candidateFlags) {
            disabled = true;
            CompatLog.fallback(MECHANISM, "sodium", "result_mismatch face=" + face
                    + " stock=" + stockFlags + " candidate=" + candidateFlags);
            return;
        }
        if (VERIFIED.incrementAndGet() == WARMUP_SAMPLES) {
            CompatLog.active(MECHANISM, "sodium");
        }
    }

    /** True only on a call that HEAD left to Sodium for comparison. */
    public static boolean verifying() {
        return !disabled && VERIFYING.get();
    }
}
