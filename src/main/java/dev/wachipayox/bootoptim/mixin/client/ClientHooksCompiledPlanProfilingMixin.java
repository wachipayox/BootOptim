package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CompiledElementsProfiler;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic attribution of NeoForge's fillNormal hook inside sampled FaceBakery calls. */
@Mixin(ClientHooks.class)
abstract class ClientHooksCompiledPlanProfilingMixin {
    @Inject(method = "fillNormal", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginFillNormal(CallbackInfo ci) {
        CompiledElementsProfiler.beginFillNormal();
    }

    @Inject(method = "fillNormal", at = @At("RETURN"), require = 0)
    private static void bootoptim$endFillNormal(CallbackInfo ci) {
        CompiledElementsProfiler.endFillNormal();
    }
}
