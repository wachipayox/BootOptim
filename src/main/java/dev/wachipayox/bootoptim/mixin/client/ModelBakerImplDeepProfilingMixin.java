package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DeepModelBakeProfiler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Function;

/** Diagnostic-only instrumentation for the private 1.21.1 ModelBakery.ModelBakerImpl. */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl")
abstract class ModelBakerImplDeepProfilingMixin {
    @Redirect(
            method = "bake(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object bootoptim$profileBakedCacheLookup(Map<?, ?> cache, Object key) {
        Object value = cache.get(key);
        DeepModelBakeProfiler.cacheLookup(value != null);
        return value;
    }

    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"))
    private void bootoptim$startUncached(
            UnbakedModel model,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        DeepModelBakeProfiler.beginUncached(model);
    }

    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"))
    private void bootoptim$endUncached(
            UnbakedModel model,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        DeepModelBakeProfiler.endUncached(model);
    }
}
