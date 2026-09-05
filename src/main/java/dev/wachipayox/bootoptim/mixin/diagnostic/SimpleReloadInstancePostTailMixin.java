package dev.wachipayox.bootoptim.mixin.diagnostic;

import dev.wachipayox.bootoptim.profiling.client.PostFancyMenuTailProfiler;
import dev.wachipayox.bootoptim.profiling.client.SimpleReloadStateFactoryBridge;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

/** Minimal #47-style observation of stock reload barriers/futures for the first startup reload. */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstancePostTailMixin<S> {
    @Shadow
    @Final
    protected CompletableFuture<Unit> allPreparations;

    @Shadow
    protected CompletableFuture<List<S>> allDone;

    @Shadow
    @Final
    private int listenerCount;

    @Unique
    private PostFancyMenuTailProfiler.ReloadTrace bootoptim$tailTrace;

    @Unique
    private boolean bootoptim$tailTraceAttempted;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            require = 0)
    private CompletableFuture<S> bootoptim$observeListener(
            @Coerce Object factory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor prepareExecutor,
            Executor applyExecutor) {
        if (!bootoptim$tailTraceAttempted) {
            bootoptim$tailTraceAttempted = true;
            bootoptim$tailTrace = PostFancyMenuTailProfiler.beginReload();
            if (bootoptim$tailTrace != null) {
                bootoptim$tailTrace.observeAllPreparations(allPreparations);
            }
        }

        PostFancyMenuTailProfiler.ReloadTrace trace = bootoptim$tailTrace;
        if (trace == null) {
            return SimpleReloadStateFactoryBridge.create(
                    factory, barrier, resourceManager, listener, prepareExecutor, applyExecutor);
        }

        PostFancyMenuTailProfiler.ListenerTrace listenerTrace = trace.addListener(listener);
        CompletableFuture<S> result = SimpleReloadStateFactoryBridge.create(
                factory,
                listenerTrace.wrapBarrier(barrier),
                resourceManager,
                listener,
                prepareExecutor,
                listenerTrace.wrapApplyExecutor(applyExecutor));
        listenerTrace.observeCompletion(result);
        return result;
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$observeReloadCompletion(CallbackInfo ci) {
        PostFancyMenuTailProfiler.ReloadTrace trace = bootoptim$tailTrace;
        if (trace == null) {
            return;
        }
        trace.setExpectedListenerCount(listenerCount);
        trace.observeAllDone(allDone);
    }
}
