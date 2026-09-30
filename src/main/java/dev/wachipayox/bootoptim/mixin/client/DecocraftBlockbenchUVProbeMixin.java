package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.DecocraftCornerQuadEquivalenceProbe;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Diagnostic-only exact logical UV inputs; original sprite calls execute once in place. */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", remap = false)
abstract class DecocraftBlockbenchUVProbeMixin {
    @WrapOperation(method = "fillVertex", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;getU(F)F"), require = 0)
    private float bootoptim$logicalU(TextureAtlasSprite sprite, float value, Operation<Float> original) {
        float result = original.call(sprite, value);
        DecocraftCornerQuadEquivalenceProbe.observeLogicalUv(sprite, value, 0);
        return result;
    }

    @WrapOperation(method = "fillVertex", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;getV(F)F"), require = 0)
    private float bootoptim$logicalV(TextureAtlasSprite sprite, float value, Operation<Float> original) {
        float result = original.call(sprite, value);
        DecocraftCornerQuadEquivalenceProbe.observeLogicalUv(sprite, value, 1);
        return result;
    }
}
