package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ShaderVoxyVarianceDiagnostic;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes Minecraft's existing GL debug callback; it never installs or replaces a callback. */
@Mixin(targets = "com.mojang.blaze3d.platform.GlDebug")
abstract class GlDebugShaderCompilerDiagnosticMixin {
    private static final int GL_DEBUG_SOURCE_SHADER_COMPILER = 33352;

    @Inject(method = "printDebugLog", at = @At("HEAD"), require = 0)
    private static void bootoptim$captureShaderCompilerMessage(
            int source,
            int type,
            int id,
            int severity,
            int length,
            long message,
            long userParam,
            CallbackInfo ci) {
        if (!ShaderVoxyVarianceDiagnostic.isEnabled() || source != GL_DEBUG_SOURCE_SHADER_COMPILER) {
            return;
        }

        String decoded;
        try {
            decoded = GLDebugMessageCallback.getMessage(length, message);
        } catch (RuntimeException ignored) {
            decoded = "decode_failed";
        }
        ShaderVoxyVarianceDiagnostic.recordShaderCompilerDebug(decoded);
    }
}
