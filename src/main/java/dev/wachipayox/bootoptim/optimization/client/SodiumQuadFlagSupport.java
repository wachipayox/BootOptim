package dev.wachipayox.bootoptim.optimization.client;

import net.neoforged.fml.ModList;
import org.slf4j.LoggerFactory;

/** Initialized only on an enabled candidate's first eligible quad. */
public final class SodiumQuadFlagSupport {
    private SodiumQuadFlagSupport() {}

    public static boolean supported() {
        return Version.SUPPORTED;
    }

    private static final class Version {
        private static final boolean SUPPORTED = resolve();

        private static boolean resolve() {
            ModList mods = ModList.get();
            String version = mods == null ? "unavailable" : mods.getModContainerById("sodium")
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("absent");
            boolean supported = version.equals("0.8.12-beta.1+mc1.21.1");
            LoggerFactory.getLogger("BootOptim/SodiumAxisQuadFlags").info(
                    "BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status={} version={}",
                    supported ? "active" : "stock-fallback", version);
            return supported;
        }
    }
}
