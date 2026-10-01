package dev.wachipayox.bootoptim.profiling.client;

/** Diagnostic only. The owner changes a mask between fully completed resource generations. */
public final class TrialFeatureGate {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.benchmark.ferritePhaseTrials");
    public static final int DECOCRAFT = 1, FERRITE = 2, SODIUM = 4, LAYER = 8;
    private static volatile int mask;
    private TrialFeatureGate() {}
    public static boolean allows(int feature) { return !ENABLED || (mask & feature) != 0; }
    public static void select(int feature) {
        if (!ENABLED || (feature != 0 && feature != DECOCRAFT && feature != FERRITE
                && feature != SODIUM && feature != LAYER)) throw new IllegalStateException("Invalid trial mask");
        mask = feature;
    }
    public static int mask() { return mask; }
}
