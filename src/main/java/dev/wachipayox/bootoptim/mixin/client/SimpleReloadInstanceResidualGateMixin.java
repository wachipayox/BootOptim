package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceReloadGateProfiler;
import dev.wachipayox.bootoptim.profiling.client.SimpleReloadStateFactoryBridge;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Diagnostic-only gate tracing with stock executors left untouched. */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceResidualGateMixin<S> {
    @Shadow @Final protected CompletableFuture<Unit> allPreparations;
    @Shadow protected CompletableFuture<List<S>> allDone;
    @Shadow @Final private int listenerCount;

    @Unique private ResourceReloadGateProfiler.ReloadTrace bootoptim$reloadTrace;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<S> bootoptim$profileListener(
            @Coerce Object factory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor prepareExecutor,
            Executor applyExecutor) {
        if (bootoptim$reloadTrace == null) bootoptim$reloadTrace = ResourceReloadGateProfiler.begin();
        ResourceReloadGateProfiler.ReloadTrace trace = bootoptim$reloadTrace;
        if (trace == null) {
            return SimpleReloadStateFactoryBridge.create(factory, barrier, resourceManager, listener, prepareExecutor, applyExecutor);
        }
        ResourceReloadGateProfiler.ListenerTrace listenerTrace = trace.addListener(listener);
        CompletableFuture<S> result = SimpleReloadStateFactoryBridge.create(
                factory, listenerTrace.wrapBarrier(barrier), resourceManager, listener, prepareExecutor, applyExecutor);
        listenerTrace.observeCompletion(result);
        return result;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$observeReloadCompletion(CallbackInfo ci) {
        ResourceReloadGateProfiler.ReloadTrace trace = bootoptim$reloadTrace;
        if (trace == null) return;
        trace.setExpectedListeners(listenerCount);
        trace.observeAllPreparations(allPreparations);
        trace.observeAllDone(allDone);
    }
}
