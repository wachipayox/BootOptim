package dev.wachipayox.bootoptim.mixin.diagnostic;

import com.mojang.blaze3d.platform.Window;
import dev.wachipayox.bootoptim.profiling.client.PostFancyMenuTailProfiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the first Window.updateDisplay RETURN after a completed first TitleScreen render. */
@Mixin(Window.class)
abstract class WindowPostTailMixin {
    @Inject(method = "updateDisplay", at = @At("RETURN"), require = 0)
    private void bootoptim$markTitlePresentReturn(CallbackInfo ci) {
        if (PostFancyMenuTailProfiler.markTitlePresentReturn()) {
            Minecraft.getInstance().stop();
        }
    }
}
