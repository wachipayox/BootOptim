package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.client.FlywheelShaderSourcesProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records actual display completion after title opening and the first presented world frame. */
@Mixin(Minecraft.class)
abstract class MinecraftFlywheelProbePresentMixin {
    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void bootoptim$afterDisplayUpdate(boolean renderLevel, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.level != null && renderLevel) {
            FlywheelShaderSourcesProbe.markFirstWorldRender();
        }
        if (FlywheelShaderSourcesProbe.markPresentedTitle() && StartupProfiler.shouldExitOnTitle()) {
            minecraft.stop();
        }
    }
}
