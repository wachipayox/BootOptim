package dev.wachipayox.bootoptim.optimization.client;

import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.fml.ModList;
import org.slf4j.LoggerFactory;

/** Bounded experiment; not production. Verification compares against the actual original return. */
public final class FerriteQuadHashTrial {
    public static final boolean REQUESTED = Boolean.getBoolean("boot_optim.ferriteStripedHash");
    public static final boolean VERIFY = Boolean.getBoolean("boot_optim.ferriteStripedHashVerify");
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final ThreadLocal<Boolean> REPLAY_MODE = new ThreadLocal<>();
    private FerriteQuadHashTrial() {}

    public static boolean eligible(int[] values) {
        return REQUESTED && values != null && values.length == 32 && Version.SUPPORTED;
    }

    public static void selectReplay(Boolean candidate) {
        if (candidate == null) REPLAY_MODE.remove(); else REPLAY_MODE.set(candidate);
    }

    public static boolean selected() {
        if (!FerriteHashReplay.ENABLED) return true;
        Boolean override = REPLAY_MODE.get();
        return override == null || override;
    }

    public static void verify(int candidate, int original) {
        if (candidate != original) throw new IllegalStateException("Ferrite striped hash differs from original");
        long count = CHECKS.incrementAndGet();
        if (count == 1 || count == 8192) LoggerFactory.getLogger("BootOptim/FerriteHashTrial").info(
                "BOOTOPTIM_FERRITE_STRIPED_HASH_VERIFY checks={} mismatches=0", count);
    }

    private static final class Version {
        private static final boolean SUPPORTED = resolve();
        private static boolean resolve() {
            ModList mods = ModList.get();
            String version = mods == null ? "unavailable" : mods.getModContainerById("ferritecore")
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("absent");
            boolean supported = version.equals("7.0.3");
            LoggerFactory.getLogger("BootOptim/FerriteHashTrial").info(
                    "BOOTOPTIM_FERRITE_STRIPED_HASH status={} version={} verify={}",
                    supported ? "active" : "stock-fallback", version, VERIFY);
            return supported;
        }
    }
}
