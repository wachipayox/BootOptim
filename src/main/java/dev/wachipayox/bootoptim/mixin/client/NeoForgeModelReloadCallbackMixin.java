package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadCallbackProfiler;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Separates arbitrary NeoForge model event callbacks from stock/replay model work. */
@Mixin(value = ClientHooks.class, remap = false)
abstract class NeoForgeModelReloadCallbackMixin {
    @Inject(method = "onModifyBakingResult", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginModifyBakingResult(
            Map<ModelResourceLocation, BakedModel> models,
            Map<ResourceLocation, AtlasSet.StitchResult> stitchResults,
            ModelBakery modelBakery,
            CallbackInfo ci) {
        ModelReloadCallbackProfiler.beginModifyBakingResult();
    }

    @Inject(method = "onModifyBakingResult", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModifyBakingResult(
            Map<ModelResourceLocation, BakedModel> models,
            Map<ResourceLocation, AtlasSet.StitchResult> stitchResults,
            ModelBakery modelBakery,
            CallbackInfo ci) {
        ModelReloadCallbackProfiler.endModifyBakingResult();
    }

    @Inject(method = "onModelBake", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginBakingCompleted(
            ModelManager modelManager,
            Map<ModelResourceLocation, BakedModel> models,
            ModelBakery modelBakery,
            CallbackInfo ci) {
        ModelReloadCallbackProfiler.beginBakingCompleted();
    }

    @Inject(method = "onModelBake", at = @At("RETURN"), require = 0)
    private static void bootoptim$endBakingCompleted(
            ModelManager modelManager,
            Map<ModelResourceLocation, BakedModel> models,
            ModelBakery modelBakery,
            CallbackInfo ci) {
        ModelReloadCallbackProfiler.endBakingCompleted();
    }
}
