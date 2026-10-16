package dev.wachipayox.bootoptim;

import dev.wachipayox.bootoptim.compat.client.CompatInjectionCheck;
import dev.wachipayox.bootoptim.optimization.client.CitResewnGlowingTrimLegacyWarningSuppression;
import dev.wachipayox.bootoptim.optimization.client.FmlEventTraceProducerFilter;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.client.ClientStartupHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.RegisterEvent;

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

    public BootOptim(IEventBus modEventBus) {
        StartupProfiler.markModEntrypoint();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FmlEventTraceProducerFilter.install();
            CitResewnGlowingTrimLegacyWarningSuppression.install(modEventBus);
            modEventBus.addListener(FMLClientSetupEvent.class, event -> CompatInjectionCheck.reportMissingPseudoTargets());
            // Common setup runs after the first reload has started, so it is not a registration boundary.
            modEventBus.addListener(FMLCommonSetupEvent.class, event -> StartupProfiler.markPhase("common_setup_start"));
            // One mark per registry dispatch: shows the registration timeline inside the mod-loading segment.
            modEventBus.addListener(EventPriority.LOWEST, RegisterEvent.class,
                    event -> StartupProfiler.markPhase("register_" + event.getRegistryKey().location().getPath()));
            if (StartupProfiler.isEnabled()) {
                ClientStartupHooks.install();
            }
        }
    }
}
