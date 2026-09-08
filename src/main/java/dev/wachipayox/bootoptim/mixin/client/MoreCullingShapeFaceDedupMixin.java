package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.MoreCullingShapeFaceDedup;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the pure VoxelShape operation instead of MoreCulling's injected
 * BlockStateBase method. This avoids an order-dependent redirect collision
 * while keeping the candidate independent of MoreCulling internals.
 */
@Mixin(value = VoxelShape.class, remap = false)
public abstract class MoreCullingShapeFaceDedupMixin {
    @Inject(method = "getFaceShape", at = @At("HEAD"), cancellable = true, remap = false)
    private void bootoptim$readDeduplicatedFace(Direction direction, CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape cached = MoreCullingShapeFaceDedup.cached((VoxelShape) (Object) this, direction);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getFaceShape", at = @At("RETURN"), remap = false)
    private void bootoptim$recordFace(Direction direction, CallbackInfoReturnable<VoxelShape> cir) {
        MoreCullingShapeFaceDedup.record((VoxelShape) (Object) this, direction, cir.getReturnValue());
    }
}
