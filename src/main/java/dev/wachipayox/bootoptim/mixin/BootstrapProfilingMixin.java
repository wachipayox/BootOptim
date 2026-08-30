package dev.wachipayox.bootoptim.mixin;

import dev.wachipayox.bootoptim.profiling.BootstrapPhaseProfiler;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Bootstrap.class)
abstract class BootstrapProfilingMixin {
    @Inject(method = "bootStrap", at = @At("HEAD"))
    private static void bootoptim$bootstrapStart(CallbackInfo ci) {
        BootstrapPhaseProfiler.begin("bootstrap_total");
    }

    @Inject(method = "bootStrap", at = @At("RETURN"))
    private static void bootoptim$bootstrapEnd(CallbackInfo ci) {
        BootstrapPhaseProfiler.end("bootstrap_total");
    }
}
