package dev.wachipayox.bootoptim.mixin.diagnostic;

import dev.wachipayox.bootoptim.profiling.client.PostFancyMenuTailProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes only the RETURN of FancyMenu's original startup preLoadAll call. */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuPostTailMixin {
    @Inject(method = "preLoadAll", at = @At("RETURN"), require = 0)
    private static void bootoptim$markPreloadReturn(long waitForCompletedMillis, CallbackInfo ci) {
        PostFancyMenuTailProfiler.markPreloadReturn();
    }
}
