package dev.wachipayox.bootoptim.mixin.client;

import com.google.gson.JsonObject;
import dev.wachipayox.bootoptim.profiling.client.ResourcePipelineProfiler;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Diagnostic-only decomposition of the stock 1.21.1 ModelManager resource futures. */
@Mixin(ModelManager.class)
abstract class ModelManagerResourceDecompositionMixin {
    @Unique private static final ThreadLocal<Long> bootoptim$modelEnumStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$modelScheduleStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$modelCollectStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$modelTaskStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$stateEnumStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$stateScheduleStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$stateCollectStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$stateTaskStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<ResourceLocation> bootoptim$currentStateId = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Resource> bootoptim$currentStateResource = new ThreadLocal<>();

    @Inject(method = "lambda$loadBlockModels$7", at = @At("HEAD"), require = 0)
    private static void bootoptim$startModelEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        if (!ResourcePipelineProfiler.enabled()) return;
        bootoptim$modelEnumStart.set(ResourcePipelineProfiler.start());
        ResourcePipelineProfiler.enterContext("block_models.enumeration");
    }

    @Inject(method = "lambda$loadBlockModels$7", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModelEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$modelEnumStart.get();
        bootoptim$modelEnumStart.remove();
        ResourcePipelineProfiler.exitContext();
        long items = cir.getReturnValue() instanceof Map<?, ?> map ? map.size() : -1L;
        ResourcePipelineProfiler.recordWallScope("block_models.enumeration", started, items);
    }

    @Inject(method = "lambda$loadBlockModels$10", at = @At("HEAD"), require = 0)
    private static void bootoptim$startModelScheduling(Executor executor, Map<?, ?> resources, CallbackInfoReturnable<?> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$modelScheduleStart.set(ResourcePipelineProfiler.start());
        }
    }

    @Inject(method = "lambda$loadBlockModels$10", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModelScheduling(Executor executor, Map<?, ?> resources, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$modelScheduleStart.get();
        bootoptim$modelScheduleStart.remove();
        ResourcePipelineProfiler.recordWallScope("block_models.schedule_futures", started, resources == null ? -1L : resources.size());
    }

    @Inject(method = "lambda$loadBlockModels$8", at = @At("HEAD"), require = 0)
    private static void bootoptim$startModelTask(Map.Entry<ResourceLocation, Resource> entry, CallbackInfoReturnable<?> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$modelTaskStart.set(ResourcePipelineProfiler.start());
        }
    }

    @Inject(method = "lambda$loadBlockModels$8", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModelTask(Map.Entry<ResourceLocation, Resource> entry, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$modelTaskStart.get();
        bootoptim$modelTaskStart.remove();
        if (entry != null && entry.getValue() != null) {
            ResourcePipelineProfiler.recordResource(
                    "block_models.resource_task",
                    entry.getKey(),
                    entry.getValue().sourcePackId(),
                    started);
        } else {
            ResourcePipelineProfiler.recordTask("block_models.resource_task", started, 1L);
        }
    }

    @Redirect(
            method = "lambda$loadBlockModels$8",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/renderer/block/model/BlockModel;"),
            require = 0)
    private static BlockModel bootoptim$timeBlockModelParse(Reader reader) {
        long started = ResourcePipelineProfiler.start();
        try {
            return BlockModel.fromStream(reader);
        } finally {
            ResourcePipelineProfiler.recordTask("block_models.json_parse_inclusive", started, 1L);
        }
    }

    @Inject(method = "lambda$loadBlockModels$9", at = @At("HEAD"), require = 0)
    private static void bootoptim$startModelCollect(List<?> values, CallbackInfoReturnable<?> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$modelCollectStart.set(ResourcePipelineProfiler.start());
        }
    }

    @Inject(method = "lambda$loadBlockModels$9", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModelCollect(List<?> values, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$modelCollectStart.get();
        bootoptim$modelCollectStart.remove();
        ResourcePipelineProfiler.recordWallScope("block_models.collect_map", started, values == null ? -1L : values.size());
    }

    @Inject(method = "lambda$loadBlockStates$11", at = @At("HEAD"), require = 0)
    private static void bootoptim$startStateEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        if (!ResourcePipelineProfiler.enabled()) return;
        bootoptim$stateEnumStart.set(ResourcePipelineProfiler.start());
        ResourcePipelineProfiler.enterContext("block_states.enumeration");
    }

    @Inject(method = "lambda$loadBlockStates$11", at = @At("RETURN"), require = 0)
    private static void bootoptim$endStateEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$stateEnumStart.get();
        bootoptim$stateEnumStart.remove();
        ResourcePipelineProfiler.exitContext();
        long items = cir.getReturnValue() instanceof Map<?, ?> map ? map.size() : -1L;
        ResourcePipelineProfiler.recordWallScope("block_states.enumeration", started, items);
    }

    @Inject(method = "lambda$loadBlockStates$14", at = @At("HEAD"), require = 0)
    private static void bootoptim$startStateScheduling(Executor executor, Map<?, ?> resources, CallbackInfoReturnable<?> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$stateScheduleStart.set(ResourcePipelineProfiler.start());
        }
    }

    @Inject(method = "lambda$loadBlockStates$14", at = @At("RETURN"), require = 0)
    private static void bootoptim$endStateScheduling(Executor executor, Map<?, ?> resources, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$stateScheduleStart.get();
        bootoptim$stateScheduleStart.remove();
        ResourcePipelineProfiler.recordWallScope("block_states.schedule_futures", started, resources == null ? -1L : resources.size());
    }

    @Inject(method = "lambda$loadBlockStates$12", at = @At("HEAD"), require = 0)
    private static void bootoptim$startStateTask(Map.Entry<ResourceLocation, List<Resource>> entry, CallbackInfoReturnable<?> cir) {
        if (!ResourcePipelineProfiler.enabled()) return;
        bootoptim$stateTaskStart.set(ResourcePipelineProfiler.start());
        if (entry != null) {
            bootoptim$currentStateId.set(entry.getKey());
        }
    }

    @Redirect(
            method = "lambda$loadBlockStates$12",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/Resource;openAsReader()Ljava/io/BufferedReader;"),
            require = 0)
    private static BufferedReader bootoptim$timeStateOpen(Resource resource) throws IOException {
        bootoptim$currentStateResource.set(resource);
        long started = ResourcePipelineProfiler.start();
        try {
            return resource.openAsReader();
        } finally {
            ResourcePipelineProfiler.recordTask("block_states.open_reader", started, 1L);
        }
    }

    @Redirect(
            method = "lambda$loadBlockStates$12",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/GsonHelper;parse(Ljava/io/Reader;)Lcom/google/gson/JsonObject;"),
            require = 0)
    private static JsonObject bootoptim$timeStateJson(Reader reader) {
        Resource resource = bootoptim$currentStateResource.get();
        ResourceLocation id = bootoptim$currentStateId.get();
        long started = ResourcePipelineProfiler.start();
        try {
            return GsonHelper.parse(reader);
        } finally {
            if (resource != null) {
                ResourcePipelineProfiler.recordResource(
                        "block_states.json_parse_inclusive",
                        id,
                        resource.sourcePackId(),
                        started);
            } else {
                ResourcePipelineProfiler.recordTask("block_states.json_parse_inclusive", started, 1L);
            }
            bootoptim$currentStateResource.remove();
        }
    }

    @Inject(method = "lambda$loadBlockStates$12", at = @At("RETURN"), require = 0)
    private static void bootoptim$endStateTask(Map.Entry<ResourceLocation, List<Resource>> entry, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$stateTaskStart.get();
        bootoptim$stateTaskStart.remove();
        bootoptim$currentStateId.remove();
        bootoptim$currentStateResource.remove();
        ResourcePipelineProfiler.recordTask(
                "block_states.resource_stack_task",
                started,
                entry == null || entry.getValue() == null ? -1L : entry.getValue().size());
    }

    @Inject(method = "lambda$loadBlockStates$13", at = @At("HEAD"), require = 0)
    private static void bootoptim$startStateCollect(List<?> values, CallbackInfoReturnable<?> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$stateCollectStart.set(ResourcePipelineProfiler.start());
        }
    }

    @Inject(method = "lambda$loadBlockStates$13", at = @At("RETURN"), require = 0)
    private static void bootoptim$endStateCollect(List<?> values, CallbackInfoReturnable<?> cir) {
        long started = bootoptim$stateCollectStart.get();
        bootoptim$stateCollectStart.remove();
        ResourcePipelineProfiler.recordWallScope("block_states.collect_map", started, values == null ? -1L : values.size());
    }
}
