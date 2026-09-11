package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DecocraftEligibilityProbe;
import dev.wachipayox.bootoptim.profiling.client.ModelDagDependencies;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadDagTrace;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only coarse ModelManager scheduling, preparation, load and commit boundaries. */
@Mixin(ModelManager.class)
abstract class ModelManagerDagMixin {
    @Unique private static final ThreadLocal<Long> BOOTOPTIM$BLOCK_MODELS_TASK = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> BOOTOPTIM$BLOCK_MODELS_GENERATION = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> BOOTOPTIM$BLOCK_STATES_TASK = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> BOOTOPTIM$BLOCK_STATES_GENERATION = new ThreadLocal<>();

    @Unique private long bootoptim$generation;
    @Unique private long bootoptim$reloadScheduleTask;
    @Unique private long bootoptim$loadModelsTask;
    @Unique private long bootoptim$commitTask;

    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$reloadHead(CallbackInfoReturnable<?> cir) {
        bootoptim$generation = ResourceReloadDagTrace.listenerGeneration();
        ResourceReloadDagTrace.beginModelManager(bootoptim$generation);
        DecocraftEligibilityProbe.beginGeneration(bootoptim$generation);
        bootoptim$reloadScheduleTask = ResourceReloadDagTrace.beginLexicalTask(
                "model_manager_reload_schedule", bootoptim$generation, null);
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$reloadReturn(CallbackInfoReturnable<?> cir) {
        long taskId = bootoptim$reloadScheduleTask;
        bootoptim$reloadScheduleTask = 0L;
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_manager_reload_schedule", "returned_future");
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadDagTrace.observeModelManagerCompletion(bootoptim$generation, future);
        }
    }

    @Inject(method = "loadBlockModels", at = @At("HEAD"))
    private static void bootoptim$blockModelsHead(CallbackInfoReturnable<?> cir) {
        long generation = ResourceReloadDagTrace.activeModelGeneration();
        BOOTOPTIM$BLOCK_MODELS_GENERATION.set(generation);
        long taskId = ResourceReloadDagTrace.beginLexicalTask("model_manager_block_models_schedule", generation, null);
        BOOTOPTIM$BLOCK_MODELS_TASK.set(taskId);
        ResourceReloadDagTrace.beginAsyncPhase("model_manager_block_models_preparation", generation, taskId);
    }

    @Inject(method = "loadBlockModels", at = @At("RETURN"))
    private static void bootoptim$blockModelsReturn(CallbackInfoReturnable<?> cir) {
        long generation = value(BOOTOPTIM$BLOCK_MODELS_GENERATION);
        long taskId = value(BOOTOPTIM$BLOCK_MODELS_TASK);
        BOOTOPTIM$BLOCK_MODELS_GENERATION.remove();
        BOOTOPTIM$BLOCK_MODELS_TASK.remove();
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_manager_block_models_schedule", "returned_future");
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadDagTrace.observeAsyncPhase("model_manager_block_models_preparation", generation, taskId, future);
            future.whenComplete((ignored, failure) -> DecocraftEligibilityProbe.blockModelsReady(generation, failure));
        }
    }

    @Inject(method = "loadBlockStates", at = @At("HEAD"))
    private static void bootoptim$blockStatesHead(CallbackInfoReturnable<?> cir) {
        long generation = ResourceReloadDagTrace.activeModelGeneration();
        BOOTOPTIM$BLOCK_STATES_GENERATION.set(generation);
        long taskId = ResourceReloadDagTrace.beginLexicalTask("model_manager_block_states_schedule", generation, null);
        BOOTOPTIM$BLOCK_STATES_TASK.set(taskId);
        ResourceReloadDagTrace.beginAsyncPhase("model_manager_block_states_preparation", generation, taskId);
    }

    @Inject(method = "loadBlockStates", at = @At("RETURN"))
    private static void bootoptim$blockStatesReturn(CallbackInfoReturnable<?> cir) {
        long generation = value(BOOTOPTIM$BLOCK_STATES_GENERATION);
        long taskId = value(BOOTOPTIM$BLOCK_STATES_TASK);
        BOOTOPTIM$BLOCK_STATES_GENERATION.remove();
        BOOTOPTIM$BLOCK_STATES_TASK.remove();
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_manager_block_states_schedule", "returned_future");
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadDagTrace.observeAsyncPhase("model_manager_block_states_preparation", generation, taskId, future);
        }
    }

    @Inject(method = "loadModels", at = @At("HEAD"))
    private void bootoptim$loadModelsHead(CallbackInfoReturnable<?> cir) {
        long generation = ResourceReloadDagTrace.activeModelGeneration();
        bootoptim$loadModelsTask = ResourceReloadDagTrace.beginLexicalTask(
                "model_manager_load_models", generation, ModelDagDependencies.bakeryPreparationDependency());
    }

    @Inject(method = "loadModels", at = @At("RETURN"))
    private void bootoptim$loadModelsReturn(CallbackInfoReturnable<?> cir) {
        long taskId = bootoptim$loadModelsTask;
        bootoptim$loadModelsTask = 0L;
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_manager_load_models", "load_models_return");
        ResourceReloadDagTrace.rememberLoadModelsTask(taskId);
    }

    @Inject(method = "apply", at = @At("HEAD"))
    private void bootoptim$applyHead(CallbackInfo ci) {
        long generation = ResourceReloadDagTrace.activeModelGeneration();
        bootoptim$commitTask = ResourceReloadDagTrace.beginLexicalTask(
                "model_manager_apply_commit", generation, ResourceReloadDagTrace.loadModelsDependency());
        ResourceReloadDagTrace.commitBegin(bootoptim$commitTask, generation);
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void bootoptim$applyReturn(CallbackInfo ci) {
        long generation = ResourceReloadDagTrace.activeModelGeneration();
        long taskId = bootoptim$commitTask;
        bootoptim$commitTask = 0L;
        ResourceReloadDagTrace.commitEnd(taskId, generation);
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_manager_apply_commit", "apply_return");
    }

    @Unique
    private static long value(ThreadLocal<Long> local) {
        Long value = local.get();
        return value == null ? 0L : value.longValue();
    }
}
