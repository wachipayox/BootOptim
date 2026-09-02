package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.McefFirstConsumerCeiling;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Conservative fallback: never carry a deferred CEF instance into an actual client world. */
@Mixin(Minecraft.class)
abstract class MinecraftMcefFirstConsumerBoundaryMixin {
    @Inject(
            method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/ReceivingLevelScreen$Reason;)V",
            at = @At("HEAD"),
            require = 0)
    private void bootoptim$initializeMcefBeforeWorld(
            ClientLevel level,
            ReceivingLevelScreen.Reason reason,
            CallbackInfo ci) {
        if (level != null) {
            McefFirstConsumerCeiling.beforeWorldJoin();
        }
    }
}
