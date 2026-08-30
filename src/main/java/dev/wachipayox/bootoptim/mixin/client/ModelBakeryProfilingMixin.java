package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadProfiler;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

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
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$modelBakeEnd(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        ModelReloadProfiler.end("model_bake", null);
    }
}
