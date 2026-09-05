package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ShaderVoxyVarianceDiagnostic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic timing around Flywheel 1.0.6's stock max-GLSL capability probe. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.gl.GlCompat", remap = false)
abstract class FlywheelGlslProbeDiagnosticMixin {
    @Inject(method = "canCompileVersion", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginGlslProbe(
            @Coerce Object version,
            CallbackInfoReturnable<Boolean> cir) {
        ShaderVoxyVarianceDiagnostic.beginShaderProbe("flywheel", String.valueOf(version));
    }

    @Inject(method = "canCompileVersion", at = @At("RETURN"), require = 0)
    private static void bootoptim$endGlslProbe(
            @Coerce Object version,
            CallbackInfoReturnable<Boolean> cir) {
        ShaderVoxyVarianceDiagnostic.endShaderProbe(
                "flywheel", String.valueOf(version), Boolean.TRUE.equals(cir.getReturnValue()));
    }
}
