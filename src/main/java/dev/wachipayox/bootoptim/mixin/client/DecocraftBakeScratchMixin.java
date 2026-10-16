package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.wachipayox.bootoptim.optimization.client.DecocraftBakeScratch;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Private staging for one {@code BlockbenchBakery.bakeQuad}: the four {@code VertexData} and the
 * selected position are reused instead of allocated per quad; see {@link DecocraftBakeScratch}.
 * Priority 1100 keeps the original-body check of {@code calculateFacing} ahead of the private
 * direction mixin.
 */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", priority = 1100, remap = false)
abstract class DecocraftBakeScratchMixin {
    @Unique private volatile DecocraftBakeScratch bootoptim$bakeScratch;

    @Unique
    private synchronized DecocraftBakeScratch bootoptim$createBakeScratch() {
        if (bootoptim$bakeScratch == null) {
            bootoptim$bakeScratch = new DecocraftBakeScratch();
        }
        return bootoptim$bakeScratch;
    }

    @Inject(method = "bakeQuad", at = @At("HEAD"), require = 0)
    private void bootoptim$beginBake(CallbackInfoReturnable<BakedQuad> result) {
        if (DecocraftBakeScratch.active()) {
            DecocraftBakeScratch scratch = bootoptim$bakeScratch;
            if (scratch == null) {
                scratch = bootoptim$createBakeScratch();
            }
            scratch.enter();
        }
    }

    @Inject(method = "bakeQuad", at = @At("RETURN"), require = 0)
    private void bootoptim$endBake(CallbackInfoReturnable<BakedQuad> result) {
        DecocraftBakeScratch scratch = bootoptim$bakeScratch;
        if (scratch != null) {
            scratch.exit();
        }
    }

    // Local index 16 is the vertex loop counter of makeVertexData; it is protected by the exact method-body contract.
    @WrapOperation(
            method = "makeVertexData",
            at = @At(value = "NEW", target = "()Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery$VertexData;"),
            require = 0)
    @Coerce
    private Object bootoptim$stagingVertex(Operation<Object> original, @Local(index = 16) int index) {
        DecocraftBakeScratch scratch = bootoptim$bakeScratch;
        if (scratch == null || !DecocraftBakeScratch.active()) {
            return original.call();
        }
        Object value = scratch.vertex(index);
        if (value != null) {
            return value;
        }
        value = original.call();
        scratch.rememberVertex(index, value);
        return value;
    }

    @WrapOperation(
            method = "bakeVertex",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery;selectPosition(Lnet/minecraft/client/renderer/FaceInfo$VertexInfo;FFFFFF)Lcom/razz/decocraft/models/libgdx/Vector3;"),
            require = 0)
    @Coerce
    private Object bootoptim$position(FaceInfo.VertexInfo vertex, float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ, Operation<Object> original) {
        DecocraftBakeScratch scratch = bootoptim$bakeScratch;
        if (scratch == null || !DecocraftBakeScratch.active()) {
            return original.call(vertex, minX, minY, minZ, maxX, maxY, maxZ);
        }
        float x = vertex.xFace == FaceInfo.Constants.MAX_X ? maxX : minX;
        float y = vertex.yFace == FaceInfo.Constants.MAX_Y ? maxY : minY;
        float z = vertex.zFace == FaceInfo.Constants.MAX_Z ? maxZ : minZ;
        Object value = scratch.position(x, y, z);
        if (value != null) {
            return value;
        }
        value = original.call(vertex, minX, minY, minZ, maxX, maxY, maxZ);
        scratch.rememberPosition(value);
        return value;
    }
}
