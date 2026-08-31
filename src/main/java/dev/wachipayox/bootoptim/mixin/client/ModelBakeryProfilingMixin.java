package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CustomGeometryBakeProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelBakeDistributionProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelReloadProfiler;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fine-grained timings inside ModelManager's asynchronous preparation path. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryProfilingMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", ordinal = 0, shift = At.Shift.AFTER))
    private void bootoptim$modelBakeryStart(BlockColors blockColors, ProfilerFiller profiler, Map<ResourceLocation, BlockModel> models, Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStates, CallbackInfo ci) {
        ModelReloadProfiler.begin("model_bakery_construct");
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$modelBakeryEnd(BlockColors blockColors, ProfilerFiller profiler, Map<ResourceLocation, BlockModel> models, Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStates, CallbackInfo ci) {
        ModelReloadProfiler.end("model_bakery_construct", null);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$modelBakeStart(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        ModelReloadProfiler.begin("model_bake");
        CustomGeometryBakeProfiler.begin();
    }

    @Redirect(
            method = "bakeModels",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void bootoptim$profileTopLevelBakeDistribution(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> bakeAction) {
        ModelBakeDistributionProfiler.profile(models, bakeAction);
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$modelBakeEnd(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        CustomGeometryBakeProfiler.finish();
        ModelReloadProfiler.end("model_bake", null);
    }
}
