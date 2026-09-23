package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact historical pack class, optional; failed calls retain an unmatched start. */
@Pseudo
@Mixin(targets = "schm.shsupercm.citresewn.cit.ActiveCITs", remap = false)
abstract class CitResewnVarianceSplitMixin {
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$CIT = new ThreadLocal<>();

    @Inject(method = "load", at = @At("HEAD"), require = 0)
    private static void bootoptim$begin(CallbackInfo ci) {
        if (VarianceProbe.enabled()) BOOTOPTIM$CIT.set(VarianceProbe.start("cit_active_load"));
    }

    @Inject(method = "load", at = @At("RETURN"), require = 0)
    private static void bootoptim$end(CallbackInfo ci) {
        if (!VarianceProbe.enabled()) return;
        var stamp = BOOTOPTIM$CIT.get();
        BOOTOPTIM$CIT.remove();
        VarianceProbe.finish("cit_active_load", stamp);
    }
}
