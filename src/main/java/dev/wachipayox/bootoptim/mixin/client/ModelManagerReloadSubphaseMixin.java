package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadSubphaseProfiler;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Diagnostic-only subdivision of ModelManager preparation after production promotions. */
@Mixin(ModelManager.class)
abstract class ModelManagerReloadSubphaseMixin {
    @Unique private static final ThreadLocal<Long> bootoptim$blockModelsStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$blockStatesStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private long bootoptim$loadModelsStart = -1L;

    @Inject(method = "loadBlockModels", at = @At("HEAD"))
    private static void bootoptim$startBlockModels(CallbackInfoReturnable<?> cir) {
        if (ModelReloadSubphaseProfiler.enabled()) bootoptim$blockModelsStart.set(ModelReloadSubphaseProfiler.start());
    }

    @Inject(method = "loadBlockModels", at = @At("RETURN"))
    private static void bootoptim$observeBlockModels(CallbackInfoReturnable<?> cir) {
        long started = bootoptim$blockModelsStart.get();
        bootoptim$blockModelsStart.remove();
        if (started <= 0L || !(cir.getReturnValue() instanceof CompletableFuture<?> future)) return;
        ModelReloadSubphaseProfiler.observeFuture("block_models", started, future);
    }

    @Inject(method = "loadBlockStates", at = @At("HEAD"))
    private static void bootoptim$startBlockStates(CallbackInfoReturnable<?> cir) {
        if (ModelReloadSubphaseProfiler.enabled()) bootoptim$blockStatesStart.set(ModelReloadSubphaseProfiler.start());
    }

    @Inject(method = "loadBlockStates", at = @At("RETURN"))
    private static void bootoptim$observeBlockStates(CallbackInfoReturnable<?> cir) {
        long started = bootoptim$blockStatesStart.get();
        bootoptim$blockStatesStart.remove();
        if (started <= 0L || !(cir.getReturnValue() instanceof CompletableFuture<?> future)) return;
        ModelReloadSubphaseProfiler.observeFuture("block_states", started, future);
    }

    @Inject(method = "loadModels", at = @At("HEAD"))
    private void bootoptim$startLoadModels(CallbackInfoReturnable<?> cir) {
        if (ModelReloadSubphaseProfiler.enabled()) bootoptim$loadModelsStart = ModelReloadSubphaseProfiler.start();
    }

    @Inject(method = "loadModels", at = @At("RETURN"))
    private void bootoptim$endLoadModels(CallbackInfoReturnable<?> cir) {
        long started = bootoptim$loadModelsStart;
        bootoptim$loadModelsStart = -1L;
        ModelReloadSubphaseProfiler.endSync("load_models", started);
    }
}
