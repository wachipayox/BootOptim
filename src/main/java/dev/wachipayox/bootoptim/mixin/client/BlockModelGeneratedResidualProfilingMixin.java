package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelElementResidualProfiler;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** Diagnostic-only timing of the generated-item BlockModel#bakeVanilla path. */
@Mixin(BlockModel.class)
abstract class BlockModelGeneratedResidualProfilingMixin {
    @Inject(method = "bakeVanilla", at = @At("HEAD"))
    private void bootoptim$beginGeneratedBake(
            ModelBaker baker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState state,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        ModelElementResidualProfiler.beginGenerated();
    }

    @Inject(method = "bakeVanilla", at = @At("RETURN"))
    private void bootoptim$endGeneratedBake(
            ModelBaker baker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState state,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        ModelElementResidualProfiler.endGenerated();
    }

    @Redirect(
            method = "bakeVanilla",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeFace(Lnet/minecraft/client/renderer/block/model/BlockElement;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)Lnet/minecraft/client/renderer/block/model/BakedQuad;"))
    private BakedQuad bootoptim$profileGeneratedFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state) {
        return ModelElementResidualProfiler.profileGeneratedFace(element, face, sprite, direction, state);
    }
}
