package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.profiling.client.MeasuredQuadSet;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.neoforged.fml.ModList;

/** Bounded follow-up: retain an integer hint, never empty storage across preparation. */
public final class FerriteCoreQuadPresize {
    public static final int MAX_EXPECTED = 1 << 20;
    private static final boolean REQUESTED = Boolean.getBoolean("boot_optim.ferriteCoreQuadPresize");
    private static volatile Boolean supported;
    private static volatile int pending;
    private FerriteCoreQuadPresize() {}

    public static boolean enabled() {
        if (!REQUESTED || !TrialFeatureGate.allows(TrialFeatureGate.FERRITE)) return false;
        if (supported == null) {
            try {
                supported = ModList.get().getModContainerById("ferritecore")
                        .map(c -> "7.0.3".equals(c.getModInfo().getVersion().toString())).orElse(false);
            } catch (RuntimeException failure) { supported = false; }
        }
        return supported;
    }

    private static boolean exact(ObjectOpenCustomHashSet<?> set) {
        return set.getClass() == ObjectOpenCustomHashSet.class
                || (TrialFeatureGate.ENABLED && set.getClass() == MeasuredQuadSet.class);
    }

    /** Called after original clear; original zero-argument trim is untouched. */
    public static void remember(ObjectOpenCustomHashSet<?> set, int previous, boolean active) {
        pending = active && previous > 0 && exact(set) && set.isEmpty()
                ? Math.min(previous, MAX_EXPECTED) : 0;
    }

    /** Called at original addOrGet under Ferrite's existing table mutex. */
    public static void beforeInsert(ObjectOpenCustomHashSet<?> set) {
        int expected = pending;
        if (expected == 0) return;
        pending = 0; // Consume once, including a failed or ineligible reservation.
        if (!exact(set) || !set.isEmpty()) return;
        // Exact 8.5.12 API is public; no reflection, weaker hash or replacement table.
        // Stock rehash publishes fields only after allocating/filling the new array.
        try { set.ensureCapacity(expected); }
        catch (OutOfMemoryError cannotReserve) { /* Stock addOrGet still executes. */ }
    }

    public static int pendingExpected() { return pending; }
}
