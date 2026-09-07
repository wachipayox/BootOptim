package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FancyMenu boundary marker only; the original preload work and ordering are untouched. */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuVarianceMarkerMixin {
    @Unique
    private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$PRELOAD = new ThreadLocal<>();

    @Inject(method = "preLoadAll", at = @At("HEAD"), require = 0)
    private static void bootoptim$preloadStart(long waitForCompletedMillis, CallbackInfo ci) {
        BOOTOPTIM$PRELOAD.set(VarianceProbe.start("fancymenu_preload"));
    }

    @Inject(method = "preLoadAll", at = @At("RETURN"), require = 0)
    private static void bootoptim$preloadEnd(long waitForCompletedMillis, CallbackInfo ci) {
        VarianceProbe.Stamp started = BOOTOPTIM$PRELOAD.get();
        BOOTOPTIM$PRELOAD.remove();
        VarianceProbe.finish("fancymenu_preload", started);
    }
}
