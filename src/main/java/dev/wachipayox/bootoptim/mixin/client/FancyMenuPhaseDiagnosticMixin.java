package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ShaderVoxyVarianceDiagnostic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Broad phase-only timing; does not instrument or replace FancyMenu's individual waits. */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuPhaseDiagnosticMixin {
    @Inject(method = "preLoadAll", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginPreload(long waitForCompletedMillis, CallbackInfo ci) {
        ShaderVoxyVarianceDiagnostic.beginFancyMenuPreload();
    }

    @Inject(method = "preLoadAll", at = @At("RETURN"), require = 0)
    private static void bootoptim$endPreload(long waitForCompletedMillis, CallbackInfo ci) {
        ShaderVoxyVarianceDiagnostic.endFancyMenuPreload();
    }
}
