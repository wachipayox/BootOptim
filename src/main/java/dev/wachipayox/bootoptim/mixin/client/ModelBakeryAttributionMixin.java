package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelBakeryAttributionProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelBakeryPrepareAttribution;
import java.util.Collection;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only attribution inside the already traced ModelBakery prepare/bake tasks. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryAttributionMixin {
    @Invoker("loadItemModelAndDependencies")
    abstract void bootoptim$invokeLoadItemModelAndDependencies(ResourceLocation location);

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void bootoptim$prepareHead(CallbackInfo ci) {
        ModelBakeryPrepareAttribution.beginConstructor();
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadAllBlockStates()V"))
    private void bootoptim$profileBlockStateRegistration(BlockStateModelLoader loader) {
        if (!ModelBakeryPrepareAttribution.enabled()) {
            loader.loadAllBlockStates();
            return;
        }
        long started = ModelBakeryPrepareAttribution.start();
        try {
            loader.loadAllBlockStates();
        } finally {
            ModelBakeryPrepareAttribution.recordBlockStates(started);
        }
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelBakery;loadItemModelAndDependencies(Lnet/minecraft/resources/ResourceLocation;)V"))
    private void bootoptim$profileItemDependencies(ModelBakery instance, ResourceLocation location) {
        if (!ModelBakeryPrepareAttribution.enabled()) {
            this.bootoptim$invokeLoadItemModelAndDependencies(location);
            return;
        }
        long started = ModelBakeryPrepareAttribution.start();
        try {
            this.bootoptim$invokeLoadItemModelAndDependencies(location);
        } finally {
            ModelBakeryPrepareAttribution.recordItem(location, started);
        }
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void bootoptim$profileParentResolution(Collection<UnbakedModel> models, Consumer<UnbakedModel> resolver) {
        if (!ModelBakeryPrepareAttribution.enabled()) {
            models.forEach(resolver);
            return;
        }
        long started = ModelBakeryPrepareAttribution.start();
        try {
            models.forEach(resolver);
        } finally {
            ModelBakeryPrepareAttribution.recordParentResolution(started, models.size());
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$prepareReturn(CallbackInfo ci) {
        ModelBakeryPrepareAttribution.finishConstructor();
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$bakeHead(CallbackInfo ci) {
        ModelBakeryAttributionProfiler.beginBakeModels();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$bakeReturn(CallbackInfo ci) {
        ModelBakeryAttributionProfiler.finishBakeModels();
    }
}
