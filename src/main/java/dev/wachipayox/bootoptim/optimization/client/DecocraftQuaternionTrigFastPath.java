package dev.wachipayox.bootoptim.optimization.client;

import java.util.concurrent.atomic.LongAdder;

/**
 * Exact-input transcendental reuse for Decocraft 3.0.11's shaded libGDX quaternion path.
 *
 * <p>The replacement values are computed once by the running JVM with the same Math.sin/cos
 * implementation. Runtime calls are replaced only when the post-normalization half-angle has an
 * exact raw-double match for a common Blockbench rotation. Every other input delegates to Math.
 */
public final class DecocraftQuaternionTrigFastPath {
    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("boot_optim.decocraftQuaternionTrigFastPath", "true")
    );
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("boot_optim.decocraftQuaternionTrigDiagnostics");

    // Raw doubles observed after Decocraft's float degrees->radians, modulo 2pi and /2 pipeline.
    // Covers +/-22.5, +/-45, +/-90, 180, +270 and -270 degree element/group rotations.
    private static final long K_22_5 = 0x3fc921fb60000000L;
    private static final long K_NEG_22_5 = 0x40078fdba0000000L;
    private static final long K_45 = 0x3fd921fb60000000L;
    private static final long K_NEG_45 = 0x4005fdbc00000000L;
    private static final long K_90 = 0x3fe921fb60000000L;
    private static final long K_NEG_90_OR_270 = 0x4002d97c80000000L;
    private static final long K_180 = 0x3ff921fb60000000L;
    private static final long K_NEG_270 = 0x3fe921fb80000000L;

    private static final double S_22_5 = Math.sin(Double.longBitsToDouble(K_22_5));
    private static final double S_NEG_22_5 = Math.sin(Double.longBitsToDouble(K_NEG_22_5));
    private static final double S_45 = Math.sin(Double.longBitsToDouble(K_45));
    private static final double S_NEG_45 = Math.sin(Double.longBitsToDouble(K_NEG_45));
    private static final double S_90 = Math.sin(Double.longBitsToDouble(K_90));
    private static final double S_NEG_90_OR_270 = Math.sin(Double.longBitsToDouble(K_NEG_90_OR_270));
    private static final double S_180 = Math.sin(Double.longBitsToDouble(K_180));
    private static final double S_NEG_270 = Math.sin(Double.longBitsToDouble(K_NEG_270));

    private static final double C_22_5 = Math.cos(Double.longBitsToDouble(K_22_5));
    private static final double C_NEG_22_5 = Math.cos(Double.longBitsToDouble(K_NEG_22_5));
    private static final double C_45 = Math.cos(Double.longBitsToDouble(K_45));
    private static final double C_NEG_45 = Math.cos(Double.longBitsToDouble(K_NEG_45));
    private static final double C_90 = Math.cos(Double.longBitsToDouble(K_90));
    private static final double C_NEG_90_OR_270 = Math.cos(Double.longBitsToDouble(K_NEG_90_OR_270));
    private static final double C_180 = Math.cos(Double.longBitsToDouble(K_180));
    private static final double C_NEG_270 = Math.cos(Double.longBitsToDouble(K_NEG_270));

    private static final LongAdder TOTAL_CALLS = new LongAdder();
    private static final LongAdder FAST_HITS = new LongAdder();

    private DecocraftQuaternionTrigFastPath() {}

    public static double sin(double input) {
        if (DIAGNOSTICS) TOTAL_CALLS.increment();
        if (!ENABLED) return Math.sin(input);
        long bits = Double.doubleToRawLongBits(input);
        double result = knownSin(bits);
        if (!Double.isNaN(result)) {
            if (DIAGNOSTICS) FAST_HITS.increment();
            return result;
        }
        return Math.sin(input);
    }

    public static double cos(double input) {
        if (DIAGNOSTICS) TOTAL_CALLS.increment();
        if (!ENABLED) return Math.cos(input);
        long bits = Double.doubleToRawLongBits(input);
        double result = knownCos(bits);
        if (!Double.isNaN(result)) {
            if (DIAGNOSTICS) FAST_HITS.increment();
            return result;
        }
        return Math.cos(input);
    }

    private static double knownSin(long bits) {
        if (bits == K_22_5) return S_22_5;
        if (bits == K_NEG_22_5) return S_NEG_22_5;
        if (bits == K_45) return S_45;
        if (bits == K_NEG_45) return S_NEG_45;
        if (bits == K_90) return S_90;
        if (bits == K_NEG_90_OR_270) return S_NEG_90_OR_270;
        if (bits == K_180) return S_180;
        if (bits == K_NEG_270) return S_NEG_270;
        return Double.NaN;
    }

    private static double knownCos(long bits) {
        if (bits == K_22_5) return C_22_5;
        if (bits == K_NEG_22_5) return C_NEG_22_5;
        if (bits == K_45) return C_45;
        if (bits == K_NEG_45) return C_NEG_45;
        if (bits == K_90) return C_90;
        if (bits == K_NEG_90_OR_270) return C_NEG_90_OR_270;
        if (bits == K_180) return C_180;
        if (bits == K_NEG_270) return C_NEG_270;
        return Double.NaN;
    }

    public static void beginModelBake() {
        if (DIAGNOSTICS) {
            TOTAL_CALLS.reset();
            FAST_HITS.reset();
        }
    }

    public static void finishModelBake() {
        if (!DIAGNOSTICS) return;
        long total = TOTAL_CALLS.sum();
        long hits = FAST_HITS.sum();
        double pct = total == 0 ? 0.0 : (100.0 * hits / total);
        System.out.printf(
            java.util.Locale.ROOT,
            "BOOTOPTIM_DECOCRAFT_QUAT_TRIG enabled=%s total_calls=%d fast_hits=%d fast_pct=%.3f%n",
            ENABLED, total, hits, pct
        );
    }
}
