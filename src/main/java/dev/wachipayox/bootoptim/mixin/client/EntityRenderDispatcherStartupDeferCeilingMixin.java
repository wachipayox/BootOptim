package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only ceiling: skip the first entity-renderer reconstruction to prove whether title
 * startup actually consumes the renderer maps. No lazy fallback is implemented on this branch.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherStartupDeferCeilingMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentRendererReloadDeferCeiling", "false"));

    @Unique
    private boolean bootoptim$initialReloadSeen;

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), cancellable = true, require = 1)
    private void bootoptim$skipInitialEntityRendererReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || this.bootoptim$initialReloadSeen) {
            this.bootoptim$initialReloadSeen = true;
            return;
        }

        this.bootoptim$initialReloadSeen = true;
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_RENDERER_RELOAD_DEFER_CEILING dispatcher=entity status=skipped thread={}",
                Thread.currentThread().getName());
        ci.cancel();
    }
}
