package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.diagnostic.client.NativeExitProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks an explicit Minecraft client stop request during opt-in native-exit diagnostics. */
@Mixin(Minecraft.class)
public abstract class MinecraftNativeExitProbeMixin {
    @Inject(method = "stop", at = @At("HEAD"), require = 0)
    private void bootOptim$markMinecraftStop(CallbackInfo ci) {
        NativeExitProbe.mark("minecraft_stop");
    }
}
