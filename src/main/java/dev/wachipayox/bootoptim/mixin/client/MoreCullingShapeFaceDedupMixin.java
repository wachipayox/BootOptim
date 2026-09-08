package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.MoreCullingShapeFaceDedup;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies only when MoreCulling has added its initShapeCache method. With the
 * default-false property the redirected call remains stock-equivalent.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", remap = false)
public abstract class MoreCullingShapeFaceDedupMixin {
    @Redirect(
            method = "moreculling$initShapeCache",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/VoxelShape;getFaceShape(Lnet/minecraft/core/Direction;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            ),
            remap = false
    )
    private VoxelShape bootoptim$deduplicateFaceShape(VoxelShape shape, Direction direction) {
        return MoreCullingShapeFaceDedup.face(shape, direction);
    }
}
