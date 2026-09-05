package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler;
import net.minecraft.world.level.block.CrossCollisionBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact source attribution for fence/iron-bars style immutable shape arrays. */
@Mixin(CrossCollisionBlock.class)
abstract class CrossCollisionBlockVoxelShapeProfilingMixin {
    @Inject(method = "makeShapes", at = @At("HEAD"))
    private void bootoptim$beginCrossCollisionShapes(
            float nodeWidth,
            float extensionWidth,
            float nodeHeight,
            float extensionBottom,
            float extensionHeight,
            CallbackInfoReturnable<?> cir) {
        VoxelShapeStartupProfiler.beginCrossCollisionShapes(
                (CrossCollisionBlock) (Object) this,
                nodeWidth,
                extensionWidth,
                nodeHeight,
                extensionBottom,
                extensionHeight);
    }

    @Inject(method = "makeShapes", at = @At("RETURN"))
    private void bootoptim$endCrossCollisionShapes(
            float nodeWidth,
            float extensionWidth,
            float nodeHeight,
            float extensionBottom,
            float extensionHeight,
            CallbackInfoReturnable<?> cir) {
        VoxelShapeStartupProfiler.endCrossCollisionShapes(cir.getReturnValue());
    }
}
