package dev.wachipayox.bootoptim;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.client.ClientStartupHooks;
import dev.wachipayox.bootoptim.optimization.client.MoreCullingShapeFaceDedup;
import dev.wachipayox.bootoptim.optimization.client.MoreCullingShapeFaceObjectCache;
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
        MoreCullingShapeFaceDedup.beginStartup();
        MoreCullingShapeFaceObjectCache.beginStartup();
        if (StartupProfiler.isEnabled() && FMLEnvironment.dist == Dist.CLIENT) {
            ClientStartupHooks.install();
        }
    }
}
