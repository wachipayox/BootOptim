package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.datafixers.util.Unit;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadCriticalPathProfiler;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Diagnostic-only universal listener tracing. The original barrier, futures and executors are
 * always delegated; this mixin only records when stock scheduling milestones actually happen.
 */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceCriticalPathMixin<S> {
    @Shadow
    @Final
    protected CompletableFuture<Unit> allPreparations;

    @Shadow
    protected CompletableFuture<List<S>> allDone;

    @Unique
    private ResourceReloadCriticalPathProfiler.ReloadTrace bootoptim$reloadTrace;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<S> bootoptim$profileListener(
            SimpleReloadInstance.StateFactory<S> factory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor prepareExecutor,
            Executor applyExecutor) {
        if (bootoptim$reloadTrace == null) {
            bootoptim$reloadTrace = ResourceReloadCriticalPathProfiler.begin();
        }
        ResourceReloadCriticalPathProfiler.ReloadTrace reloadTrace = bootoptim$reloadTrace;
        if (reloadTrace == null) {
            return factory.create(barrier, resourceManager, listener, prepareExecutor, applyExecutor);
        }

        ResourceReloadCriticalPathProfiler.ListenerTrace listenerTrace = reloadTrace.addListener(listener);
        CompletableFuture<S> result = factory.create(
                listenerTrace.wrapBarrier(barrier),
                resourceManager,
                listener,
                listenerTrace.wrapPrepareExecutor(prepareExecutor),
                listenerTrace.wrapApplyExecutor(applyExecutor));
        listenerTrace.observeCompletion(result);
        return result;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$observeReloadCompletion(
            Executor prepareExecutor,
            Executor applyExecutor,
            ResourceManager resourceManager,
            List<PreparableReloadListener> listeners,
            SimpleReloadInstance.StateFactory<S> stateFactory,
            CompletableFuture<Unit> initialStage,
            CallbackInfo ci) {
        ResourceReloadCriticalPathProfiler.ReloadTrace reloadTrace = bootoptim$reloadTrace;
        if (reloadTrace == null) {
            return;
        }
        reloadTrace.setExpectedListenerCount(listeners.size());
        reloadTrace.observeAllPreparations(allPreparations);
        reloadTrace.observeAllDone(allDone);
    }
}
