package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks the ModelManager preparation future without changing its identity or ordering. */
@Mixin(ModelManager.class)
abstract class ModelManagerVoxelShapePhaseMixin {
    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$beginModelReload(
            PreparableReloadListener.PreparationBarrier preparationBarrier,
            ResourceManager resourceManager,
            ProfilerFiller preparationsProfiler,
            ProfilerFiller reloadProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        VoxelShapeStartupProfiler.beginModelReload();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$observeModelReload(
            PreparableReloadListener.PreparationBarrier preparationBarrier,
            ResourceManager resourceManager,
            ProfilerFiller preparationsProfiler,
            ProfilerFiller reloadProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        VoxelShapeStartupProfiler.observeModelReloadFuture(cir.getReturnValue());
    }
}
