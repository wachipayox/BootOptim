package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.blaze3d.platform.Window;
import dev.wachipayox.bootoptim.profiling.client.PostReloadMenuTrace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only boundary immediately after Minecraft's stock display update call.
 *
 * <p>The target is the normal GAME-layer {@link Minecraft#runTick(boolean)} callsite rather than
 * {@link Window} itself. This observes the return from the existing {@code Window.updateDisplay()}
 * invocation without transforming the window/native class, invoking GLFW, or changing render-thread ownership.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPresentTraceMixin {
    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void bootoptim$afterDisplayUpdate(boolean renderLevel, CallbackInfo ci) {
        PostReloadMenuTrace.onWindowPresentReturn();
    }
}
