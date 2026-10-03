package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Avoids Xaero World Map's synchronous online update check on the render thread. */
@Pseudo
@Mixin(targets = "xaero.map.WorldMap", remap = false)
abstract class XaeroWorldMapUpdateCheckMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();
    @Unique
    private static final String BOOTOPTIM$EXPECTED_VERSION = "1.41.0";
    @Unique
    private static final boolean BOOTOPTIM$SKIP_UPDATE_CHECK = Boolean.parseBoolean(
            System.getProperty("boot_optim.xaeroSkipStartupUpdateCheck", "true"));

    @WrapOperation(method = "loadLater()V", at = @At(value = "INVOKE",
            target = "Lxaero/map/misc/Internet;checkModVersion()V", remap = false), require = 0)
    private void bootoptim$skipSynchronousUpdateCheck(Operation<Void> original) {
        if (!BOOTOPTIM$SKIP_UPDATE_CHECK || !bootoptim$isExpectedVersion()) {
            original.call();
            return;
        }
        BOOTOPTIM$LOGGER.info("BOOTOPTIM_XAERO_UPDATE_CHECK status=skipped version={} reason=render_thread_network_wait",
                BOOTOPTIM$EXPECTED_VERSION);
    }

    @Unique
    private static boolean bootoptim$isExpectedVersion() {
        try {
            return BOOTOPTIM$EXPECTED_VERSION.equals(ModList.get()
                    .getModContainerById("xaeroworldmap")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse(null));
        } catch (RuntimeException failure) {
            BOOTOPTIM$LOGGER.warn("BOOTOPTIM_XAERO_UPDATE_CHECK status=stock reason=version_probe_failed", failure);
            return false;
        }
    }
}
