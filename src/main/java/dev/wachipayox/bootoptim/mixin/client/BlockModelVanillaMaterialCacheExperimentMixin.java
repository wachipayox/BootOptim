package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.profiling.client.ShortScopeMaterialCacheExperiment;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/** Diagnostic A/B experiment for repeated BlockModel#getMaterial calls inside one bakeVanilla invocation. */
@Mixin(BlockModel.class)
abstract class BlockModelVanillaMaterialCacheExperimentMixin {
    @WrapMethod(method = "bakeVanilla")
    private BakedModel bootoptim$shortScopeMaterialCache(
            ModelBaker baker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            Operation<BakedModel> original) {
        ShortScopeMaterialCacheExperiment.beginVanilla();
        try {
            return original.call(baker, owner, spriteGetter, modelState, guiLight3d);
        } finally {
            ShortScopeMaterialCacheExperiment.endScope();
        }
    }

    @Redirect(
            method = "bakeVanilla",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;getMaterial(Ljava/lang/String;)Lnet/minecraft/client/resources/model/Material;"))
    private Material bootoptim$cacheVanillaMaterial(BlockModel model, String name) {
        return ShortScopeMaterialCacheExperiment.resolve(model, name);
    }
}
