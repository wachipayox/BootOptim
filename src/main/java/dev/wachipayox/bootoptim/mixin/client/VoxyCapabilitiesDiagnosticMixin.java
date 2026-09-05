package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ShaderVoxyVarianceDiagnostic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic timing around Voxy's stock capability shader compile helper. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.Capabilities", remap = false)
abstract class VoxyCapabilitiesDiagnosticMixin {
    @Inject(method = "testShaderCompilesOk", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginCapabilityProbe(
            @Coerce Object shaderType,
            String source,
            CallbackInfoReturnable<Boolean> cir) {
        ShaderVoxyVarianceDiagnostic.beginShaderProbe(
                "voxy", ShaderVoxyVarianceDiagnostic.versionFromSource(source));
    }

    @Inject(method = "testShaderCompilesOk", at = @At("RETURN"), require = 0)
    private static void bootoptim$endCapabilityProbe(
            @Coerce Object shaderType,
            String source,
            CallbackInfoReturnable<Boolean> cir) {
        ShaderVoxyVarianceDiagnostic.endShaderProbe(
                "voxy",
                ShaderVoxyVarianceDiagnostic.versionFromSource(source),
                Boolean.TRUE.equals(cir.getReturnValue()));
    }
}
