package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FancyMenuWaitCpuDiagnostic;
import dev.wachipayox.bootoptim.profiling.client.FancyMenuWaitCpuDiagnostic.Family;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only timing around FancyMenu's existing preload waits.
 *
 * <p>The before/after callbacks surround the original INVOKE instructions. They do not redirect,
 * cancel, replace, sleep, park, or alter the resource/timeout/error path.</p>
 */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuWaitCpuDiagnosticMixin {
    private static final String RESOURCE_WAIT =
            "Lde/keksuccino/fancymenu/util/resource/Resource;waitForLoadingCompletedOrFailed(J)V";
    private static final String TEXTURE_WAIT =
            "Lde/keksuccino/fancymenu/util/resource/resources/texture/ITexture;waitForLoadingCompletedOrFailed(J)V";

    @Inject(method = "preLoadAll", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginPreLoad(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.beginPreLoad();
    }

    @Inject(
            method = "preLoadAll",
            at = @At(value = "INVOKE", target = RESOURCE_WAIT),
            require = 0)
    private static void bootoptim$beforeOrdinaryWait(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.beforeWait(Family.ORDINARY);
    }

    @Inject(
            method = "preLoadAll",
            at = @At(value = "INVOKE", target = RESOURCE_WAIT, shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterOrdinaryWait(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.afterWait(Family.ORDINARY);
    }

    @Inject(
            method = "preLoadSlideshow",
            at = @At(value = "INVOKE", target = TEXTURE_WAIT),
            require = 0)
    private static void bootoptim$beforeSlideshowWait(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.beforeWait(Family.SLIDESHOW);
    }

    @Inject(
            method = "preLoadSlideshow",
            at = @At(value = "INVOKE", target = TEXTURE_WAIT, shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterSlideshowWait(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.afterWait(Family.SLIDESHOW);
    }

    @Inject(
            method = "preLoadCubicPanorama",
            at = @At(value = "INVOKE", target = TEXTURE_WAIT),
            require = 0)
    private static void bootoptim$beforePanoramaWait(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.beforeWait(Family.PANORAMA);
    }

    @Inject(
            method = "preLoadCubicPanorama",
            at = @At(value = "INVOKE", target = TEXTURE_WAIT, shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterPanoramaWait(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.afterWait(Family.PANORAMA);
    }

    @Inject(method = "preLoadAll", at = @At("RETURN"), require = 0)
    private static void bootoptim$finishPreLoad(CallbackInfo ci) {
        FancyMenuWaitCpuDiagnostic.finishPreLoad();
    }
}
