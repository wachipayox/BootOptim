package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelReloadProfiler;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Splits ModelBakery construction into the expensive semantic phases without changing its lifecycle.
 * This is profiling only; all original calls still execute synchronously and in their original order.
 */
@Mixin(ModelBakery.class)
abstract class ModelBakeryConstructorProfilingMixin {
    @Shadow
    private abstract void loadItemModelAndDependencies(ResourceLocation location);

    @Unique
    private long bootoptim$itemModelNanos;

    @Unique
    private int bootoptim$itemModelCount;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadAllBlockStates()V"))
    private void bootoptim$profileBlockStateRegistration(BlockStateModelLoader loader) {
        if (!StartupProfiler.isEnabled()) {
            loader.loadAllBlockStates();
            return;
        }

        long started = System.nanoTime();
        Throwable failure = null;
        try {
            loader.loadAllBlockStates();
        } catch (RuntimeException | Error throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            ModelReloadProfiler.record(
                    "model_bakery_blockstates",
                    System.nanoTime() - started,
                    failure,
                    1);
        }
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelBakery;loadItemModelAndDependencies(Lnet/minecraft/resources/ResourceLocation;)V"))
    private void bootoptim$profileItemModel(ModelBakery instance, ResourceLocation location) {
        if (!StartupProfiler.isEnabled()) {
            this.loadItemModelAndDependencies(location);
            return;
        }

        long started = System.nanoTime();
        try {
            this.loadItemModelAndDependencies(location);
        } finally {
            this.bootoptim$itemModelNanos += System.nanoTime() - started;
            this.bootoptim$itemModelCount++;
        }
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void bootoptim$profileParentResolution(Collection<UnbakedModel> models, Consumer<UnbakedModel> resolver) {
        if (!StartupProfiler.isEnabled()) {
            models.forEach(resolver);
            return;
        }

        long started = System.nanoTime();
        Throwable failure = null;
        try {
            models.forEach(resolver);
        } catch (RuntimeException | Error throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            ModelReloadProfiler.record(
                    "model_bakery_resolve_parents",
                    System.nanoTime() - started,
                    failure,
                    models.size());
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$reportItemModelRegistration(
            BlockColors blockColors,
            ProfilerFiller profiler,
            Map<ResourceLocation, BlockModel> models,
            Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStates,
            CallbackInfo ci) {
        if (StartupProfiler.isEnabled()) {
            ModelReloadProfiler.record(
                    "model_bakery_items",
                    this.bootoptim$itemModelNanos,
                    null,
                    this.bootoptim$itemModelCount);
        }
    }
}
