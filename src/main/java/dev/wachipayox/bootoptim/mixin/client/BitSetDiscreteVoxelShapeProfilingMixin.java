package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact constructor count plus sparse stack attribution for BitSetDiscreteVoxelShape creation. */
@Mixin(BitSetDiscreteVoxelShape.class)
abstract class BitSetDiscreteVoxelShapeProfilingMixin {
    @Inject(method = "<init>(III)V", at = @At("RETURN"))
    private void bootoptim$recordSizedConstruction(int xSize, int ySize, int zSize, CallbackInfo ci) {
        VoxelShapeStartupProfiler.recordBitSetConstruction(xSize, ySize, zSize, "sized");
    }

    @Inject(
            method = "<init>(Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape;)V",
            at = @At("RETURN"))
    private void bootoptim$recordCopyConstruction(DiscreteVoxelShape source, CallbackInfo ci) {
        VoxelShapeStartupProfiler.recordBitSetConstruction(
                source.getXSize(), source.getYSize(), source.getZSize(), "copy");
    }
}
