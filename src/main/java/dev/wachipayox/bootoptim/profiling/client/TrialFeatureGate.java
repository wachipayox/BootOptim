package dev.wachipayox.bootoptim.profiling.client;

/** Diagnostic mode selector, only changed between complete offscreen replay blocks. */
public final class TrialFeatureGate {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.benchmark.sodiumPhaseReplay");
    public static final int SODIUM = 4;
    private static volatile int mask;
    public static boolean allows(int feature) { return !ENABLED || mask == feature; }
    public static void select(int selected) {
        if (!ENABLED || (selected != 0 && selected != SODIUM)) throw new IllegalStateException("Invalid mode");
        mask = selected;
    }
    private TrialFeatureGate() {}
}
