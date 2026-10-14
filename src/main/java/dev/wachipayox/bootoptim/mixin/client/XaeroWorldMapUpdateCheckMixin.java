package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.compat.client.CompatLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Avoids Xaero World Map's synchronous online update check on the render thread. Not pinned to a
 * Xaero version: if {@code loadLater()} no longer calls {@code checkModVersion()}, the injection
 * finds no target and the post-apply check logs {@code [WBOMBO-COMPAT] FALLBACK}.
 */
@Pseudo
@Mixin(targets = "xaero.map.WorldMap", remap = false)
abstract class XaeroWorldMapUpdateCheckMixin {
    @Unique
    private static final boolean BOOTOPTIM$SKIP_UPDATE_CHECK = Boolean.parseBoolean(
            System.getProperty("boot_optim.xaeroSkipStartupUpdateCheck", "true"));

    @WrapOperation(method = "loadLater()V", at = @At(value = "INVOKE",
            target = "Lxaero/map/misc/Internet;checkModVersion()V", remap = false), require = 0)
    private void bootoptim$skipSynchronousUpdateCheck(Operation<Void> original) {
        if (!BOOTOPTIM$SKIP_UPDATE_CHECK) {
            original.call();
            return;
        }
        CompatLog.active("xaero-startup-update-check-skip", "xaeroworldmap");
    }
}
