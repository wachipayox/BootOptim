package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.ModelPreparationPlanVerifier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the existing stock ModelManager future; it never wraps or replaces the future returned to Minecraft. */
@Mixin(ModelManager.class)
abstract class ModelManagerPreparationPlanVerifierMixin {
    @Unique private long bootoptim$modelPreparationBarrierStart = -1L;

    @Inject(method = "reload", at = @At("HEAD"), require = 0)
    private void bootoptim$beginPreparationPlanReload(
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resources,
            ProfilerFiller preparationProfiler,
            ProfilerFiller reloadProfiler,
            Executor preparationExecutor,
            Executor reloadExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!ModelPreparationPlanVerifier.enabled()) return;
        ModelPreparationPlanVerifier.beginReload();
        bootoptim$modelPreparationBarrierStart = ModelPreparationPlanVerifier.barrierStart();
    }

    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private void bootoptim$observePreparationPlanReload(
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resources,
            ProfilerFiller preparationProfiler,
            ProfilerFiller reloadProfiler,
            Executor preparationExecutor,
            Executor reloadExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        long started = bootoptim$modelPreparationBarrierStart;
        bootoptim$modelPreparationBarrierStart = -1L;
        if (started <= 0L) return;
        CompletableFuture<Void> stockFuture = cir.getReturnValue();
        if (stockFuture != null) {
            stockFuture.whenComplete((ignored, failure) -> ModelPreparationPlanVerifier.finishReload(started, failure));
        }
    }
}
