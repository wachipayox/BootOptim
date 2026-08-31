package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResidualModelBakeProfiler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** Diagnostic-only exclusive timing for private 1.21.1 ModelBakery.ModelBakerImpl#bakeUncached. */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl")
abstract class ModelBakerImplResidualProfilingMixin {
    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"))
    private void bootoptim$startResidualUncached(
            UnbakedModel model,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        ResidualModelBakeProfiler.beginUncached(model);
    }

    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"))
    private void bootoptim$endResidualUncached(
            UnbakedModel model,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        ResidualModelBakeProfiler.endUncached(model);
    }
}
