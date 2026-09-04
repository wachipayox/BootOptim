package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.RendererReloadCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pays any deferred renderer-reload work at the first real world transition, before the new level
 * is attached to LevelRenderer/entity/block-entity rendering state.
 */
@Mixin(Minecraft.class)
abstract class MinecraftRendererWorldWarmupMixin {
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentRendererFirstConsumerDefer", "false"));

    private static final boolean BOOTOPTIM$WORLD_WARMUP = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentRendererWorldWarmup", "true"));

    @Inject(method = "updateLevelInEngines", at = @At("HEAD"), require = 1)
    private void bootoptim$warmRenderersBeforeWorldAttachment(ClientLevel level, CallbackInfo ci) {
        if (BOOTOPTIM$ENABLED && BOOTOPTIM$WORLD_WARMUP && level != null) {
            RendererReloadCoordinator.forcePending("world_attach");
        }
    }
}
