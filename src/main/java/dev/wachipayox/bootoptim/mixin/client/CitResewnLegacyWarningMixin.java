package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CitResewnLegacyWarningFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only optional hook for CITResewn 1.21.x. */
@Pseudo
@Mixin(targets = "shcm.shsupercm.fabric.citresewn.CITResewn", remap = false)
public abstract class CitResewnLegacyWarningMixin {
    @Inject(
            method = "logWarnLoading(Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void bootoptim$filterLegacyNameDiagnostic(String message, CallbackInfo ci) {
        if (CitResewnLegacyWarningFilter.shouldSuppress(message)) {
            ci.cancel();
        }
    }
}
