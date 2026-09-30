package dev.wachipayox.bootoptim.profiling.client;

import java.util.ArrayList;
import java.util.List;

/** Fixed counterbalanced pairs. Ferrite needs a same-mode primer before each observation. */
public final class TrialPlan {
    public record Step(String feature, int mask, int pair, boolean measured, String kind) {}
    public static List<Step> steps() {
        List<Step> result = new ArrayList<>();
        result.add(new Step("warmup", 0, 0, false, "warmup"));
        append(result, "decocraft", TrialFeatureGate.DECOCRAFT, false, false);
        append(result, "sodium", TrialFeatureGate.SODIUM, true, false);
        append(result, "layer", TrialFeatureGate.LAYER, false, false);
        append(result, "ferrite", TrialFeatureGate.FERRITE, true, true);
        return List.copyOf(result);
    }
    private static void append(List<Step> target, String feature, int bit, boolean reverse, boolean prime) {
        int[] modes = reverse ? new int[]{bit, 0, 0, bit} : new int[]{0, bit, bit, 0};
        for (int i = 0; i < modes.length; i++) {
            int pair = i < 2 ? 1 : 2;
            if (prime) target.add(new Step(feature, modes[i], pair, false, "conditioning"));
            target.add(new Step(feature, modes[i], pair, true, "measurement"));
        }
    }
}
