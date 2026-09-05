package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.MoreCullingTranslucencyCache;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Defines the exact resource-reload lifetime of the MoreCulling translucency cache. */
@Mixin(ReloadableResourceManager.class)
abstract class ReloadableResourceManagerMoreCullingCacheMixin {
    @Inject(
            method = "createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;",
            at = @At("HEAD"),
            require = 0)
    private void bootoptim$beginMoreCullingCacheGeneration(
            Executor preparationExecutor,
            Executor reloadExecutor,
            CompletableFuture<Unit> initialStage,
            List<PackResources> packs,
            CallbackInfoReturnable<ReloadInstance> cir) {
        MoreCullingTranslucencyCache.beginReload();
    }

    @Inject(
            method = "createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$clearMoreCullingCacheAfterReload(
            Executor preparationExecutor,
            Executor reloadExecutor,
            CompletableFuture<Unit> initialStage,
            List<PackResources> packs,
            CallbackInfoReturnable<ReloadInstance> cir) {
        long generation = MoreCullingTranslucencyCache.currentGeneration();
        ReloadInstance reload = cir.getReturnValue();
        if (generation < 0L || reload == null) {
            return;
        }
        reload.done().whenComplete((ignored, failure) ->
                MoreCullingTranslucencyCache.endReload(generation, failure));
    }
}
