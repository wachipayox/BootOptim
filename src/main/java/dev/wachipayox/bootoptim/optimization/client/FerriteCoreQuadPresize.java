package dev.wachipayox.bootoptim.optimization.client;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.neoforged.fml.ModList;
import org.slf4j.LoggerFactory;
import dev.wachipayox.bootoptim.profiling.client.MeasuredQuadSet;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;

/** FerriteCore 7.0.3: retain an integer hint, never empty storage across preparation. */
public final class FerriteCoreQuadPresize {
    public static final int MAX_EXPECTED = 1 << 20;
    private static final boolean REQUESTED = !"false".equalsIgnoreCase(System.getProperty("boot_optim.ferriteCoreQuadPresize"));
    private static volatile Boolean supported;
    private static volatile int pending;
    private FerriteCoreQuadPresize() {}

    public static boolean enabled() {
        if (!REQUESTED) return false;
        if (supported == null) {
            try {
                supported = ModList.get().getModContainerById("ferritecore")
                        .map(c -> "7.0.3".equals(c.getModInfo().getVersion().toString())).orElse(false);
            } catch (RuntimeException failure) { supported = false; }
            LoggerFactory.getLogger("BootOptim/FerriteQuadPresize").info(
                    "BOOTOPTIM_FERRITE_QUAD_PRESIZE stage=support status={}", supported ? "active" : "stock-fallback");
        }
        return supported;
    }

    private static boolean exact(ObjectOpenCustomHashSet<?> set) {
        return set.getClass() == ObjectOpenCustomHashSet.class
                || (TrialFeatureGate.ENABLED && set.getClass() == MeasuredQuadSet.class);
    }

    /** Called after original clear; original zero-argument trim is untouched. */
    public static void remember(ObjectOpenCustomHashSet<?> set, int previous, boolean active) {
        pending = active && TrialFeatureGate.allows(TrialFeatureGate.FERRITE) && previous > 0 && exact(set) && set.isEmpty()
                ? Math.min(previous, MAX_EXPECTED) : 0;
        if (pending > 0) LoggerFactory.getLogger("BootOptim/FerriteQuadPresize").info(
                "BOOTOPTIM_FERRITE_QUAD_PRESIZE stage=remember expected={} retained_model_data=false", pending);
    }

    /** Called at original addOrGet under Ferrite's existing table mutex. */
    public static void beforeInsert(ObjectOpenCustomHashSet<?> set) {
        if (!REQUESTED) return;
        int expected = pending;
        if (expected == 0) return;
        pending = 0; // Consume once, including a failed or ineligible reservation.
        if (!exact(set) || !set.isEmpty()) return;
        // Exact 8.5.12 API is public; no reflection, weaker hash or replacement table.
        // Stock rehash publishes fields only after allocating/filling the new array.
        boolean reserved = false;
        try { set.ensureCapacity(expected); reserved = true; }
        catch (OutOfMemoryError cannotReserve) { /* Stock addOrGet still executes. */ }
        LoggerFactory.getLogger("BootOptim/FerriteQuadPresize").info(
                "BOOTOPTIM_FERRITE_QUAD_PRESIZE stage=reserve expected={} success={}", expected, reserved);
    }

    public static int pendingExpected() { return pending; }
}
