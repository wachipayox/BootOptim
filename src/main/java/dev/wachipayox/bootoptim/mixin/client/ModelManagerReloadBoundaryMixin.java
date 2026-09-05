package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler.Stamp;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only aggregate boundaries inside ModelManager reload. */
@Mixin(ModelManager.class)
abstract class ModelManagerReloadBoundaryMixin {
    @Unique
    private static final ThreadLocal<Stamp> BOOTOPTIM$BLOCK_MODELS = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Stamp> BOOTOPTIM$BLOCK_STATES = new ThreadLocal<>();
    @Unique
    private Stamp bootoptim$reloadStart;
    @Unique
    private Stamp bootoptim$loadModelsStart;

    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$reloadStart(CallbackInfoReturnable<?> cir) {
        bootoptim$reloadStart = ResourceReloadBoundaryProfiler.start();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$reloadReturned(CallbackInfoReturnable<?> cir) {
        Stamp started = bootoptim$reloadStart;
        bootoptim$reloadStart = null;
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadBoundaryProfiler.observeFuture("model_manager_reload", started, future);
        }
    }

    @Inject(method = "loadBlockModels", at = @At("HEAD"))
    private static void bootoptim$blockModelsStart(CallbackInfoReturnable<?> cir) {
        if (ResourceReloadBoundaryProfiler.enabled()) {
            BOOTOPTIM$BLOCK_MODELS.set(ResourceReloadBoundaryProfiler.start());
        }
    }

    @Inject(method = "loadBlockModels", at = @At("RETURN"))
    private static void bootoptim$blockModelsReturned(CallbackInfoReturnable<?> cir) {
        Stamp started = BOOTOPTIM$BLOCK_MODELS.get();
        BOOTOPTIM$BLOCK_MODELS.remove();
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadBoundaryProfiler.observeFuture("block_models", started, future);
        }
    }

    @Inject(method = "loadBlockStates", at = @At("HEAD"))
    private static void bootoptim$blockStatesStart(CallbackInfoReturnable<?> cir) {
        if (ResourceReloadBoundaryProfiler.enabled()) {
            BOOTOPTIM$BLOCK_STATES.set(ResourceReloadBoundaryProfiler.start());
        }
    }

    @Inject(method = "loadBlockStates", at = @At("RETURN"))
    private static void bootoptim$blockStatesReturned(CallbackInfoReturnable<?> cir) {
        Stamp started = BOOTOPTIM$BLOCK_STATES.get();
        BOOTOPTIM$BLOCK_STATES.remove();
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ResourceReloadBoundaryProfiler.observeFuture("block_states", started, future);
        }
    }

    @Inject(method = "loadModels", at = @At("HEAD"))
    private void bootoptim$loadModelsStart(CallbackInfoReturnable<?> cir) {
        bootoptim$loadModelsStart = ResourceReloadBoundaryProfiler.start();
    }

    @Inject(method = "loadModels", at = @At("RETURN"))
    private void bootoptim$loadModelsEnd(CallbackInfoReturnable<?> cir) {
        Stamp started = bootoptim$loadModelsStart;
        bootoptim$loadModelsStart = null;
        ResourceReloadBoundaryProfiler.endSync("load_models", started);
    }
}
