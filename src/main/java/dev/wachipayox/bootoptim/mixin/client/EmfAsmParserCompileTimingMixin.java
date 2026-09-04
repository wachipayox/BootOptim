package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.EmfAsmCompileRepeatProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional diagnostic hook for EMF's ASMParser without a compile/runtime dependency on EMF. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.asm.ASMParser", remap = false)
abstract class EmfAsmParserCompileTimingMixin {
    @Inject(method = "compileOrNull", at = @At("HEAD"), remap = false, require = 0)
    private static void bootoptim$beginAsmCompile(
            @Coerce Object animationHandler,
            @Coerce Object variableHandler,
            CallbackInfoReturnable<Object> cir) {
        EmfAsmCompileRepeatProfiler.beginCompile(animationHandler);
    }

    @Inject(method = "compileOrNull", at = @At("RETURN"), remap = false, require = 0)
    private static void bootoptim$endAsmCompile(
            @Coerce Object animationHandler,
            @Coerce Object variableHandler,
            CallbackInfoReturnable<Object> cir) {
        EmfAsmCompileRepeatProfiler.endCompile(
                animationHandler, variableHandler, cir.getReturnValue());
    }
}
