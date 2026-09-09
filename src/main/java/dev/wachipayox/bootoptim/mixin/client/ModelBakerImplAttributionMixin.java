package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelBakeryAttributionProfiler;
import java.util.function.Function;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes actual ModelBakerImpl cache misses. Lower priority makes this injection see the existing
 * generated-item HEAD cancellation path and its synthetic return, so frames stay balanced.
 */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl", priority = 900)
abstract class ModelBakerImplAttributionMixin {
    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"),
            require = 0)
    private void bootoptim$uncachedHead(
            UnbakedModel model,
            ModelState modelState,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        ModelBakeryAttributionProfiler.beginUncached(model);
    }

    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$uncachedReturn(
            UnbakedModel model,
            ModelState modelState,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        ModelBakeryAttributionProfiler.endUncached(model);
    }
}
