package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadProfiler;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Profiling-only hooks. No model data or futures are replaced or reordered. */
@Mixin(ModelManager.class)
abstract class ModelManagerProfilingMixin {
    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$modelReloadStart(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ModelReloadProfiler.begin("model_reload");
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$modelReloadEnd(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        CompletableFuture<Void> future = cir.getReturnValue();
        if (future != null) {
            future.whenComplete((ignored, failure) -> ModelReloadProfiler.end("model_reload", failure));
        }
    }

    @Inject(method = "loadBlockModels", at = @At("HEAD"))
    private static void bootoptim$blockModelsStart(CallbackInfoReturnable<CompletableFuture<?>> cir) {
        ModelReloadProfiler.begin("block_models_json");
    }

    @Inject(method = "loadBlockModels", at = @At("RETURN"))
    private static void bootoptim$blockModelsEnd(CallbackInfoReturnable<CompletableFuture<?>> cir) {
        CompletableFuture<?> future = cir.getReturnValue();
        if (future != null) {
            future.whenComplete((ignored, failure) -> ModelReloadProfiler.end("block_models_json", failure));
        }
    }

    @Inject(method = "loadBlockStates", at = @At("HEAD"))
    private static void bootoptim$blockStatesStart(CallbackInfoReturnable<CompletableFuture<?>> cir) {
        ModelReloadProfiler.begin("blockstates_json");
    }

    @Inject(method = "loadBlockStates", at = @At("RETURN"))
    private static void bootoptim$blockStatesEnd(CallbackInfoReturnable<CompletableFuture<?>> cir) {
        CompletableFuture<?> future = cir.getReturnValue();
        if (future != null) {
            future.whenComplete((ignored, failure) -> ModelReloadProfiler.end("blockstates_json", failure));
        }
    }
}
