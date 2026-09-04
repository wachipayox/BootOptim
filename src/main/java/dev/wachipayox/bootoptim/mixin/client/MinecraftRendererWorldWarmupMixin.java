package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.RendererReloadCoordinator;
import dev.wachipayox.bootoptim.profiling.client.RendererWorldEntryProbe;
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

    private static final boolean BOOTOPTIM$WORLD_ENTRY_PROBE = Boolean.parseBoolean(
            System.getProperty(
                    "boot_optim.experimentRendererWorldEntryProbe",
                    Boolean.toString(BOOTOPTIM$ENABLED)));

    @Inject(method = "updateLevelInEngines", at = @At("HEAD"), require = 1)
    private void bootoptim$warmRenderersBeforeWorldAttachment(ClientLevel level, CallbackInfo ci) {
        if (level == null || (!BOOTOPTIM$ENABLED && !BOOTOPTIM$WORLD_ENTRY_PROBE)) {
            return;
        }

        if (BOOTOPTIM$WORLD_ENTRY_PROBE) {
            RendererWorldEntryProbe.beginAttach(RendererReloadCoordinator.hasPending());
        }
        if (BOOTOPTIM$ENABLED && BOOTOPTIM$WORLD_WARMUP) {
            RendererReloadCoordinator.forcePending("world_attach");
        }
        if (BOOTOPTIM$WORLD_ENTRY_PROBE) {
            RendererWorldEntryProbe.finishAttachWarmup();
        }
    }
}
