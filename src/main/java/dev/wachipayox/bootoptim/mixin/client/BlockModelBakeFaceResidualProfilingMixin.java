package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelElementResidualProfiler;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only attribution of BlockModel.bakeFace calls whose BlockElement was created by ItemModelGenerator. */
@Mixin(BlockModel.class)
abstract class BlockModelBakeFaceResidualProfilingMixin {
    @Inject(
            method = "bakeFace(Lnet/minecraft/client/renderer/block/model/BlockElement;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)Lnet/minecraft/client/renderer/block/model/BakedQuad;",
            at = @At("HEAD"))
    private static void bootoptim$beginGeneratedFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state,
            CallbackInfoReturnable<BakedQuad> cir) {
        ModelElementResidualProfiler.beginGeneratedFace(element);
    }

    @Inject(
            method = "bakeFace(Lnet/minecraft/client/renderer/block/model/BlockElement;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)Lnet/minecraft/client/renderer/block/model/BakedQuad;",
            at = @At("RETURN"))
    private static void bootoptim$endGeneratedFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state,
            CallbackInfoReturnable<BakedQuad> cir) {
        ModelElementResidualProfiler.endGeneratedFace();
    }
}
