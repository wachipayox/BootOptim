package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VoxelShaperPrefixDiagnostics;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DIAGNOSTIC ONLY: observes {@code Shapes.or(a, b)} while a {@code VoxelShaper.rotatedCopy} session is open. */
@Mixin(Shapes.class)
abstract class ShapesOrPrefixDiagnosticMixin {
    private static final String OR = "or(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)"
            + "Lnet/minecraft/world/phys/shapes/VoxelShape;";

    @Inject(method = OR, at = @At("HEAD"), require = 0)
    private static void bootoptim$orEnter(VoxelShape a, VoxelShape b, CallbackInfoReturnable<VoxelShape> cir) {
        if (VoxelShaperPrefixDiagnostics.ENABLED) {
            VoxelShaperPrefixDiagnostics.orEnter(a, b);
        }
    }

    @Inject(method = OR, at = @At("RETURN"), require = 0)
    private static void bootoptim$orExit(VoxelShape a, VoxelShape b, CallbackInfoReturnable<VoxelShape> cir) {
        if (VoxelShaperPrefixDiagnostics.ENABLED) {
            VoxelShaperPrefixDiagnostics.orExit(cir.getReturnValue());
        }
    }
}
