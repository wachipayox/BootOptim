package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.ShortScopeMaterialCacheExperiment;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Establishes the short-lived material cache exactly around the generated model bakeVanilla call. */
@Mixin(UnbakedGeometryHelper.class)
abstract class UnbakedGeometryHelperGeneratedMaterialCacheExperimentMixin {
    @WrapOperation(
            method = "bake",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeVanilla(Lnet/minecraft/client/resources/model/ModelBaker;Lnet/minecraft/client/renderer/block/model/BlockModel;Ljava/util/function/Function;Lnet/minecraft/client/resources/model/ModelState;Z)Lnet/minecraft/client/resources/model/BakedModel;"))
    private static BakedModel bootoptim$scopeGeneratedMaterialCache(
            BlockModel generatedModel,
            ModelBaker baker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            Operation<BakedModel> original) {
        ShortScopeMaterialCacheExperiment.beginVanilla();
        try {
            return original.call(generatedModel, baker, owner, spriteGetter, modelState, guiLight3d);
        } finally {
            ShortScopeMaterialCacheExperiment.endScope();
        }
    }
}
