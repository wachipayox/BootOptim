package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ShaderVoxyVarianceDiagnostic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes Voxy config saves without retrying, suppressing, deleting, or changing filesystem IO. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.config.VoxyConfig", remap = false)
abstract class VoxyConfigSaveDiagnosticMixin {
    @Inject(method = "save", at = @At("HEAD"), require = 0)
    private void bootoptim$beginSave(CallbackInfo ci) {
        ShaderVoxyVarianceDiagnostic.beginVoxySave();
    }

    @Inject(method = "save", at = @At("RETURN"), require = 0)
    private void bootoptim$endSave(CallbackInfo ci) {
        ShaderVoxyVarianceDiagnostic.endVoxySave();
    }
}
