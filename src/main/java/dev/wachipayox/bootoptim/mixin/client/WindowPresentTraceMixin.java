package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.blaze3d.platform.Window;
import dev.wachipayox.bootoptim.profiling.client.PostReloadMenuTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only first-present boundary for the startup title frame.
 *
 * <p>The hook runs after stock {@link Window#updateDisplay()} returns, on the calling render thread.
 * It never calls GLFW/RenderSystem itself and never moves, wraps, delays or reschedules rendering.</p>
 */
@Mixin(Window.class)
public abstract class WindowPresentTraceMixin {
    @Inject(method = "updateDisplay", at = @At("RETURN"), require = 0)
    private void bootoptim$afterUpdateDisplay(CallbackInfo ci) {
        PostReloadMenuTrace.onWindowPresentReturn();
    }
}
