package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;
import net.neoforged.fml.ModList;

/** Default-off FerriteCore 7.0.3 experiment. No model/vertex data survives clearing. */
public final class FerriteCoreQuadCapacity {
    // Exact stock set uses load factor 0.75: at most 2^21 reference slots plus one null slot.
    public static final int MAX_EXPECTED = 1 << 20;
    private static final boolean REQUESTED = Boolean.getBoolean("boot_optim.ferriteCoreQuadCapacity");
    private static volatile Boolean supported;

    private FerriteCoreQuadCapacity() {}

    public static boolean enabled() {
        if (!REQUESTED || !TrialFeatureGate.allows(TrialFeatureGate.FERRITE)) return false;
        if (supported == null) {
            try {
                supported = ModList.get().getModContainerById("ferritecore")
                        .map(container -> "7.0.3".equals(container.getModInfo().getVersion().toString()))
                        .orElse(false);
            } catch (RuntimeException failure) {
                supported = false;
            }
        }
        return supported;
    }

    public static void report(int previous, int retained, boolean success) {
        LogUtils.getLogger().info(
                "BOOTOPTIM_FERRITE_QUAD_CAPACITY previous_unique={} retained_expected={} success={} retained_model_data=false",
                previous, retained, success);
    }
}
