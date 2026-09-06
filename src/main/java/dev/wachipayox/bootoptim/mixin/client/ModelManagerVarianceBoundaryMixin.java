package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only aggregate boundaries inside ModelManager reload. */
@Mixin(ModelManager.class)
abstract class ModelManagerVarianceBoundaryMixin {
    @Unique
    private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$BLOCK_MODELS = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$BLOCK_STATES = new ThreadLocal<>();
    @Unique
    private VarianceProbe.Stamp bootoptim$reloadStart;
    @Unique
    private VarianceProbe.Stamp bootoptim$loadModelsStart;

    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$reloadStart(CallbackInfoReturnable<?> cir) {
        bootoptim$reloadStart = ResourceReloadBoundaryProfiler.start("model_manager_reload");
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$reloadReturned(CallbackInfoReturnable<?> cir) {
        VarianceProbe.Stamp started = bootoptim$reloadStart;
        bootoptim$reloadStart = null;
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadBoundaryProfiler.observeFuture("model_manager_reload", started, future);
        }
    }

    @Inject(method = "loadBlockModels", at = @At("HEAD"))
    private static void bootoptim$blockModelsStart(CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$BLOCK_MODELS.set(ResourceReloadBoundaryProfiler.start("block_models"));
    }

    @Inject(method = "loadBlockModels", at = @At("RETURN"))
    private static void bootoptim$blockModelsReturned(CallbackInfoReturnable<?> cir) {
        VarianceProbe.Stamp started = BOOTOPTIM$BLOCK_MODELS.get();
        BOOTOPTIM$BLOCK_MODELS.remove();
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadBoundaryProfiler.observeFuture("block_models", started, future);
        }
    }

    @Inject(method = "loadBlockStates", at = @At("HEAD"))
    private static void bootoptim$blockStatesStart(CallbackInfoReturnable<?> cir) {
        BOOTOPTIM$BLOCK_STATES.set(ResourceReloadBoundaryProfiler.start("block_states"));
    }

    @Inject(method = "loadBlockStates", at = @At("RETURN"))
    private static void bootoptim$blockStatesReturned(CallbackInfoReturnable<?> cir) {
        VarianceProbe.Stamp started = BOOTOPTIM$BLOCK_STATES.get();
        BOOTOPTIM$BLOCK_STATES.remove();
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadBoundaryProfiler.observeFuture("block_states", started, future);
        }
    }

    @Inject(method = "loadModels", at = @At("HEAD"))
    private void bootoptim$loadModelsStart(CallbackInfoReturnable<?> cir) {
        bootoptim$loadModelsStart = ResourceReloadBoundaryProfiler.start("load_models");
    }

    @Inject(method = "loadModels", at = @At("RETURN"))
    private void bootoptim$loadModelsEnd(CallbackInfoReturnable<?> cir) {
        VarianceProbe.Stamp started = bootoptim$loadModelsStart;
        bootoptim$loadModelsStart = null;
        ResourceReloadBoundaryProfiler.endSync("load_models", started);
    }
}
