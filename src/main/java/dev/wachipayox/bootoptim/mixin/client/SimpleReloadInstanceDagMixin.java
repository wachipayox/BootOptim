package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceReloadDagTrace;
import dev.wachipayox.bootoptim.profiling.client.SimpleReloadStateFactoryBridge;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only first-reload generation and semantic barrier tracing. */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceDagMixin<S> {
    @Shadow @Final protected CompletableFuture<Unit> allPreparations;
    @Shadow protected CompletableFuture<List<S>> allDone;

    @Unique private long bootoptim$reloadGeneration;
    @Unique private int bootoptim$listenerIndex;
    @Unique private boolean bootoptim$modelManagerSeen;

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<S> bootoptim$traceListenerBoundary(
            @Coerce Object factory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor prepareExecutor,
            Executor applyExecutor) {
        if (bootoptim$reloadGeneration == 0L) {
            bootoptim$reloadGeneration = ResourceReloadDagTrace.beginReloadGeneration();
        }
        long generation = bootoptim$reloadGeneration;
        int listenerIndex = bootoptim$listenerIndex++;
        PreparableReloadListener.PreparationBarrier actualBarrier = barrier;
        boolean modelManager = listener instanceof ModelManager;
        boolean tailListener = bootoptim$modelManagerSeen && !modelManager;
        String listenerClassName = listener.getClass().getName();
        if (modelManager) {
            bootoptim$modelManagerSeen = true;
            actualBarrier = ResourceReloadDagTrace.wrapModelManagerBarrier(generation, barrier);
            ResourceReloadDagTrace.enterListener(generation);
        } else if (tailListener) {
            ResourceReloadDagTrace.beginTailListener(generation, listenerIndex, listenerClassName);
            actualBarrier = ResourceReloadDagTrace.wrapTailListenerBarrier(
                    generation, barrier, listenerIndex, listenerClassName);
        }
        try {
            CompletableFuture<S> future = SimpleReloadStateFactoryBridge.create(factory, actualBarrier, resourceManager,
                    listener, prepareExecutor, applyExecutor);
            if (tailListener) {
                ResourceReloadDagTrace.observeTailListenerCompletion(
                        generation, listenerIndex, listenerClassName, future);
            }
            return future;
        } finally {
            if (modelManager) {
                ResourceReloadDagTrace.exitListener();
            }
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$observeReloadFutures(CallbackInfo ci) {
        if (bootoptim$reloadGeneration == 0L) {
            bootoptim$reloadGeneration = ResourceReloadDagTrace.beginReloadGeneration();
        }
        ResourceReloadDagTrace.observeReloadFutures(bootoptim$reloadGeneration, allPreparations, allDone);
    }
}
