package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import net.minecraft.server.Bootstrap;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only observer aligned to ModernFix's vanilla Bootstrap.bootStrap stopwatch boundary. */
@Mixin(Bootstrap.class)
abstract class BootstrapVarianceBoundaryMixin {
    @Unique
    private static VarianceProbe.Stamp bootoptim$bootstrapStart;

    @Inject(method = "bootStrap", at = @At("HEAD"), require = 0)
    private static void bootoptim$prepareVarianceProbe(CallbackInfo ci) {
        // Force the diagnostic helper/MXBean handles to initialize before ModernFix's measured field boundary.
        VarianceProbe.enabled();
    }

    @Inject(
            method = "bootStrap",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.PUTSTATIC,
                    target = "Lnet/minecraft/server/Bootstrap;isBootstrapped:Z",
                    ordinal = 0),
            require = 0)
    private static void bootoptim$beginBootstrapBoundary(CallbackInfo ci) {
        bootoptim$bootstrapStart = VarianceProbe.start("vanilla_bootstrap");
    }

    @Inject(method = "bootStrap", at = @At("RETURN"), require = 0)
    private static void bootoptim$endBootstrapBoundary(CallbackInfo ci) {
        VarianceProbe.Stamp started = bootoptim$bootstrapStart;
        bootoptim$bootstrapStart = null;
        VarianceProbe.finish("vanilla_bootstrap", started);
    }
}
