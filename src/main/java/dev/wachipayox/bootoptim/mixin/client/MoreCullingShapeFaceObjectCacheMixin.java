package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.MoreCullingShapeFaceObjectCache;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostic alternative to the external identity map: each immutable
 * VoxelShape carries its six derived faces directly. It is opt-in and scoped
 * to startup, so the field stays null on the normal path.
 */
@Mixin(value = VoxelShape.class, remap = false)
public abstract class MoreCullingShapeFaceObjectCacheMixin {
    @Unique
    private VoxelShape[] bootoptim$faceCache;

    @Inject(method = "getFaceShape", at = @At("HEAD"), cancellable = true, remap = false)
    private void bootoptim$readFace(Direction direction, CallbackInfoReturnable<VoxelShape> cir) {
        if (!MoreCullingShapeFaceObjectCache.isActive() || bootoptim$faceCache == null) {
            return;
        }
        VoxelShape cached = bootoptim$faceCache[direction.ordinal()];
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getFaceShape", at = @At("RETURN"), remap = false)
    private void bootoptim$storeFace(Direction direction, CallbackInfoReturnable<VoxelShape> cir) {
        if (!MoreCullingShapeFaceObjectCache.isActive()) {
            return;
        }
        if (bootoptim$faceCache == null) {
            bootoptim$faceCache = new VoxelShape[Direction.values().length];
        }
        bootoptim$faceCache[direction.ordinal()] = cir.getReturnValue();
    }
}
