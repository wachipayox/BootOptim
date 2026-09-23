package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import dev.wachipayox.bootoptim.profiling.client.ModelInputDeepProfiler;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact 1.21.1 callsite probe; every wrapped stock call is invoked exactly once. */
@Mixin(ModelManager.class)
abstract class ModelManagerDeepInputMixin {
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$MODEL_LIST = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$STATE_LIST = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$MODEL_ENQUEUE = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$STATE_ENQUEUE = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$MODEL_COLLECT = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$STATE_COLLECT = new ThreadLocal<>();

    @Inject(method = "lambda$loadBlockModels$7", at = @At("HEAD"), remap = false)
    private static void bootoptim$modelListBegin(ResourceManager resources, CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$MODEL_LIST.set(VarianceProbe.start("model_resource_listing", "deep_" + ModelInputDeepProfiler.activeId()));
    }

    @Inject(method = "lambda$loadBlockModels$7", at = @At("RETURN"), remap = false)
    @SuppressWarnings("unchecked")
    private static void bootoptim$modelListEnd(ResourceManager resources, CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() instanceof Map<?, ?> map) {
            ModelInputDeepProfiler.modelsListed((Map<ResourceLocation, Resource>) map);
        }
        VarianceProbe.finish("model_resource_listing", "deep_" + ModelInputDeepProfiler.activeId(), BOOTOPTIM$MODEL_LIST.get());
        BOOTOPTIM$MODEL_LIST.remove();
    }

    @Inject(method = "lambda$loadBlockStates$11", at = @At("HEAD"), remap = false)
    private static void bootoptim$stateListBegin(ResourceManager resources, CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$STATE_LIST.set(VarianceProbe.start("state_resource_listing", "deep_" + ModelInputDeepProfiler.activeId()));
    }

    @Inject(method = "lambda$loadBlockStates$11", at = @At("RETURN"), remap = false)
    @SuppressWarnings("unchecked")
    private static void bootoptim$stateListEnd(ResourceManager resources, CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() instanceof Map<?, ?> map) {
            ModelInputDeepProfiler.statesListed((Map<ResourceLocation, List<Resource>>) map);
        }
        VarianceProbe.finish("state_resource_listing", "deep_" + ModelInputDeepProfiler.activeId(), BOOTOPTIM$STATE_LIST.get());
        BOOTOPTIM$STATE_LIST.remove();
    }

    @Inject(method = "lambda$loadBlockModels$10", at = @At("HEAD"), remap = false)
    private static void bootoptim$modelEnqueueBegin(Executor executor, Map<?, ?> entries, CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$MODEL_ENQUEUE.set(VarianceProbe.start("model_resource_enqueue", "deep_" + ModelInputDeepProfiler.activeId()));
    }

    @Inject(method = "lambda$loadBlockModels$10", at = @At("RETURN"), remap = false)
    private static void bootoptim$modelEnqueueEnd(Executor executor, Map<?, ?> entries, CallbackInfoReturnable<?> cir) {
        VarianceProbe.finish("model_resource_enqueue", "deep_" + ModelInputDeepProfiler.activeId(), BOOTOPTIM$MODEL_ENQUEUE.get());
        BOOTOPTIM$MODEL_ENQUEUE.remove();
    }

    @Inject(method = "lambda$loadBlockStates$14", at = @At("HEAD"), remap = false)
    private static void bootoptim$stateEnqueueBegin(Executor executor, Map<?, ?> entries, CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$STATE_ENQUEUE.set(VarianceProbe.start("state_resource_enqueue", "deep_" + ModelInputDeepProfiler.activeId()));
    }

    @Inject(method = "lambda$loadBlockStates$14", at = @At("RETURN"), remap = false)
    private static void bootoptim$stateEnqueueEnd(Executor executor, Map<?, ?> entries, CallbackInfoReturnable<?> cir) {
        VarianceProbe.finish("state_resource_enqueue", "deep_" + ModelInputDeepProfiler.activeId(), BOOTOPTIM$STATE_ENQUEUE.get());
        BOOTOPTIM$STATE_ENQUEUE.remove();
    }

    @Inject(method = "lambda$loadBlockModels$9", at = @At("HEAD"), remap = false)
    private static void bootoptim$modelCollectBegin(List<?> values, CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$MODEL_COLLECT.set(VarianceProbe.start("model_resource_collect", "deep_" + ModelInputDeepProfiler.activeId()));
    }

    @Inject(method = "lambda$loadBlockModels$9", at = @At("RETURN"), remap = false)
    private static void bootoptim$modelCollectEnd(List<?> values, CallbackInfoReturnable<?> cir) {
        VarianceProbe.finish("model_resource_collect", "deep_" + ModelInputDeepProfiler.activeId(), BOOTOPTIM$MODEL_COLLECT.get());
        BOOTOPTIM$MODEL_COLLECT.remove();
    }

    @Inject(method = "lambda$loadBlockStates$13", at = @At("HEAD"), remap = false)
    private static void bootoptim$stateCollectBegin(List<?> values, CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$STATE_COLLECT.set(VarianceProbe.start("state_resource_collect", "deep_" + ModelInputDeepProfiler.activeId()));
    }

    @Inject(method = "lambda$loadBlockStates$13", at = @At("RETURN"), remap = false)
    private static void bootoptim$stateCollectEnd(List<?> values, CallbackInfoReturnable<?> cir) {
        VarianceProbe.finish("state_resource_collect", "deep_" + ModelInputDeepProfiler.activeId(), BOOTOPTIM$STATE_COLLECT.get());
        BOOTOPTIM$STATE_COLLECT.remove();
    }

    @Inject(method = "lambda$loadBlockModels$8", at = @At("HEAD"), remap = false)
    private static void bootoptim$modelTaskBegin(Map.Entry<ResourceLocation, Resource> entry, CallbackInfoReturnable<?> cir) {
        if (VarianceProbe.enabled()) ModelInputDeepProfiler.beginModelTask(entry.getValue());
    }

    @Inject(method = "lambda$loadBlockModels$8", at = @At("RETURN"), remap = false)
    private static void bootoptim$modelTaskEnd(Map.Entry<ResourceLocation, Resource> entry, CallbackInfoReturnable<?> cir) {
        if (VarianceProbe.enabled()) ModelInputDeepProfiler.endTask(cir.getReturnValue() != null);
    }

    @Inject(method = "lambda$loadBlockStates$12", at = @At("HEAD"), remap = false)
    private static void bootoptim$stateTaskBegin(Map.Entry<?, ?> entry, CallbackInfoReturnable<?> cir) {
        if (VarianceProbe.enabled()) ModelInputDeepProfiler.beginStateTask();
    }

    @Inject(method = "lambda$loadBlockStates$12", at = @At("RETURN"), remap = false)
    private static void bootoptim$stateTaskEnd(Map.Entry<?, ?> entry, CallbackInfoReturnable<?> cir) {
        if (VarianceProbe.enabled()) ModelInputDeepProfiler.endTask(cir.getReturnValue() != null);
    }

    @WrapOperation(method = {"lambda$loadBlockModels$8", "lambda$loadBlockStates$12"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;openAsReader()Ljava/io/BufferedReader;"),
            remap = false)
    private static BufferedReader bootoptim$open(Resource source, Operation<BufferedReader> original) {
        if (!VarianceProbe.enabled()) return original.call(source);
        long started = System.nanoTime();
        boolean success = false;
        try {
            BufferedReader reader = original.call(source);
            success = true;
            return reader;
        } finally {
            ModelInputDeepProfiler.opened(source, System.nanoTime() - started, success);
        }
    }

    @WrapOperation(method = "lambda$loadBlockModels$8",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/model/BlockModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/renderer/block/model/BlockModel;"),
            remap = false)
    private static BlockModel bootoptim$parseModel(Reader reader, Operation<BlockModel> original) {
        if (!VarianceProbe.enabled()) return original.call(reader);
        long started = System.nanoTime();
        boolean success = false;
        try {
            BlockModel model = original.call(reader);
            success = true;
            return model;
        } finally {
            ModelInputDeepProfiler.parsed(System.nanoTime() - started, success);
        }
    }

    @WrapOperation(method = "lambda$loadBlockStates$12",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/GsonHelper;parse(Ljava/io/Reader;)Lcom/google/gson/JsonObject;"),
            remap = false)
    private static JsonObject bootoptim$parseState(Reader reader, Operation<JsonObject> original) {
        if (!VarianceProbe.enabled()) return original.call(reader);
        long started = System.nanoTime();
        boolean success = false;
        try {
            JsonObject parsed = original.call(reader);
            success = true;
            return parsed;
        } finally {
            ModelInputDeepProfiler.parsed(System.nanoTime() - started, success);
        }
    }
}
