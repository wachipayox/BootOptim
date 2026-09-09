package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelBakeryAttributionProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelBakeryPrepareAttribution;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only attribution inside the already traced ModelBakery prepare/bake tasks. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryAttributionMixin {
    @Invoker("loadItemModelAndDependencies")
    abstract void bootoptim$invokeLoadItemModelAndDependencies(ResourceLocation location);

    @Invoker("loadSpecialItemModelAndDependencies")
    abstract void bootoptim$invokeLoadSpecialItemModelAndDependencies(ModelResourceLocation location);

    @Invoker("loadBlockModel")
    abstract BlockModel bootoptim$invokeLoadBlockModel(ResourceLocation location) throws IOException;

    @Invoker("getModel")
    abstract UnbakedModel bootoptim$invokeGetModel(ResourceLocation location);

    @Invoker("registerModelAndLoadDependencies")
    abstract void bootoptim$invokeRegisterModelAndLoadDependencies(ModelResourceLocation location, UnbakedModel model);

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void bootoptim$prepareHead(CallbackInfo ci) {
        ModelBakeryPrepareAttribution.beginConstructor();
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelBakery;loadBlockModel(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/block/model/BlockModel;"))
    private BlockModel bootoptim$profileMissingModel(ModelBakery instance, ResourceLocation location) throws IOException {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            return this.bootoptim$invokeLoadBlockModel(location);
        } finally {
            ModelBakeryPrepareAttribution.recordMissingModel(started);
        }
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
                    target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;getModelGroups()Lit/unimi/dsi/fastutil/objects/Object2IntMap;"))
    private Object2IntMap<BlockState> bootoptim$profileModelGroups(BlockStateModelLoader loader) {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            return loader.getModelGroups();
        } finally {
            ModelBakeryPrepareAttribution.recordModelGroups(started);
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
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelBakery;loadSpecialItemModelAndDependencies(Lnet/minecraft/client/resources/model/ModelResourceLocation;)V"))
    private void bootoptim$profileSpecialModel(ModelBakery instance, ModelResourceLocation location) {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            this.bootoptim$invokeLoadSpecialItemModelAndDependencies(location);
        } finally {
            ModelBakeryPrepareAttribution.recordSpecialModel(started);
        }
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;onRegisterAdditionalModels(Ljava/util/Set;)V"))
    private void bootoptim$profileNeoForgeAdditionalEvent(Set<ModelResourceLocation> models) {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            ClientHooks.onRegisterAdditionalModels(models);
        } finally {
            ModelBakeryPrepareAttribution.recordNeoForgeAdditionalEvent(started, models.size());
        }
    }

    @Redirect(
            method = "<init>",
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onRegisterAdditionalModels(Ljava/util/Set;)V"),
                    to = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V")),
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelBakery;getModel(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/resources/model/UnbakedModel;"))
    private UnbakedModel bootoptim$profileNeoForgeAdditionalGet(ModelBakery instance, ResourceLocation location) {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            return this.bootoptim$invokeGetModel(location);
        } finally {
            ModelBakeryPrepareAttribution.recordNeoForgeAdditionalGet(location, started);
        }
    }

    @Redirect(
            method = "<init>",
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onRegisterAdditionalModels(Ljava/util/Set;)V"),
                    to = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V")),
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/model/ModelBakery;registerModelAndLoadDependencies(Lnet/minecraft/client/resources/model/ModelResourceLocation;Lnet/minecraft/client/resources/model/UnbakedModel;)V"))
    private void bootoptim$profileNeoForgeAdditionalRegister(
            ModelBakery instance, ModelResourceLocation location, UnbakedModel model) {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            this.bootoptim$invokeRegisterModelAndLoadDependencies(location, model);
        } finally {
            ModelBakeryPrepareAttribution.recordNeoForgeAdditionalRegister(location.id(), started);
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
