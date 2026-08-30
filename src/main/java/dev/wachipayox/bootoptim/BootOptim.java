package dev.wachipayox.bootoptim;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.client.ClientOverlayDiagnostics;
import dev.wachipayox.bootoptim.profiling.client.ClientStartupHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * BootOptim entry point.
 *
 * Keep this class intentionally minimal: startup instrumentation and optimizations
 * belong in narrowly scoped components so the mod itself does not add avoidable
 * work to the boot path.
 */
@Mod(BootOptim.MOD_ID)
public final class BootOptim {
    public static final String MOD_ID = "boot_optim";

    public BootOptim() {
        StartupProfiler.markModEntrypoint();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientOverlayDiagnostics.install();
            if (StartupProfiler.isEnabled()) {
                ClientStartupHooks.install();
            }
        }
    }
}
