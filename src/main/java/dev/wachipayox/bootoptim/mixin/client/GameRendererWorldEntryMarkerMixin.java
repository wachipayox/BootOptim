package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.RendererWorldEntryProbe;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the first real world render after each measured world attachment. */
@Mixin(GameRenderer.class)
abstract class GameRendererWorldEntryMarkerMixin {
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentRendererFirstConsumerDefer", "false"));

    private static final boolean BOOTOPTIM$WORLD_ENTRY_PROBE = Boolean.parseBoolean(
            System.getProperty(
                    "boot_optim.experimentRendererWorldEntryProbe",
                    Boolean.toString(BOOTOPTIM$ENABLED)));

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void bootoptim$markFirstWorldRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (BOOTOPTIM$WORLD_ENTRY_PROBE) {
            RendererWorldEntryProbe.markFirstRender();
        }
    }
}
