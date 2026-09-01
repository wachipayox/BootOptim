package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler;
import net.minecraft.world.level.block.WallBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact parameter-tuple and identity attribution for the expensive WallBlock shape-table builder. */
@Mixin(WallBlock.class)
abstract class WallBlockVoxelShapeProfilingMixin {
    @Inject(method = "makeShapes", at = @At("HEAD"))
    private void bootoptim$beginWallShapes(
            float width,
            float depth,
            float wallPostHeight,
            float wallMinY,
            float wallLowHeight,
            float wallTallHeight,
            CallbackInfoReturnable<?> cir) {
        VoxelShapeStartupProfiler.beginWallShapes(
                (WallBlock) (Object) this,
                width,
                depth,
                wallPostHeight,
                wallMinY,
                wallLowHeight,
                wallTallHeight);
    }

    @Inject(method = "makeShapes", at = @At("RETURN"))
    private void bootoptim$endWallShapes(
            float width,
            float depth,
            float wallPostHeight,
            float wallMinY,
            float wallLowHeight,
            float wallTallHeight,
            CallbackInfoReturnable<?> cir) {
        VoxelShapeStartupProfiler.endWallShapes(cir.getReturnValue());
    }
}
