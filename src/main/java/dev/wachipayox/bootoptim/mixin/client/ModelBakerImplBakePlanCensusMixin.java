package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.BakePlanCensusProfiler;
import java.util.function.Function;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the real 1.21.1 bake key and cache-miss call tree without replacing either. */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl", priority = 900)
abstract class ModelBakerImplBakePlanCensusMixin {
    @Inject(
            method = "bake(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"),
            require = 0)
    private void bootoptim$bakePlanKeyHead(
            ResourceLocation location,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        BakePlanCensusProfiler.beginKey(location, state);
    }

    @Inject(
            method = "bake(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$bakePlanKeyReturn(
            ResourceLocation location,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        BakePlanCensusProfiler.endKey();
    }

    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"),
            require = 0)
    private void bootoptim$bakePlanUncachedHead(
            UnbakedModel model,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        BakePlanCensusProfiler.beginUncached(model);
    }

    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$bakePlanUncachedReturn(
            UnbakedModel model,
            ModelState state,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        BakePlanCensusProfiler.endUncached(model);
    }
}
