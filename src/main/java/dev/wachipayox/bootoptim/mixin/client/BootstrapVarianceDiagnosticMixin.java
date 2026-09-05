package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.BootstrapVarianceDiagnostic;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only HEAD/RETURN observer for the vanilla bootstrap wall. */
@Mixin(Bootstrap.class)
abstract class BootstrapVarianceDiagnosticMixin {
    @Inject(method = "bootStrap", at = @At("HEAD"), require = 0)
    private static void bootoptim$bootstrapVarianceBegin(CallbackInfo ci) {
        BootstrapVarianceDiagnostic.begin();
    }

    @Inject(method = "bootStrap", at = @At("RETURN"), require = 0)
    private static void bootoptim$bootstrapVarianceEnd(CallbackInfo ci) {
        BootstrapVarianceDiagnostic.end();
    }
}
