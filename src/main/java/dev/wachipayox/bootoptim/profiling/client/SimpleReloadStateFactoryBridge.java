package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.datafixers.util.Unit;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Java visibility bridge for the protected 1.21.1 SimpleReloadInstance.StateFactory type.
 * The profiler mixin receives the owner as Object via Mixin @Coerce and delegates here without reflection.
 */
public abstract class SimpleReloadStateFactoryBridge<S> extends SimpleReloadInstance<S> {
    private SimpleReloadStateFactoryBridge(
            Executor prepareExecutor,
            Executor applyExecutor,
            ResourceManager resourceManager,
            List<PreparableReloadListener> listeners,
            StateFactory<S> stateFactory,
            CompletableFuture<Unit> initialStage) {
        super(prepareExecutor, applyExecutor, resourceManager, listeners, stateFactory, initialStage);
    }

    @SuppressWarnings("unchecked")
    public static <S> CompletableFuture<S> create(
            Object rawFactory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor prepareExecutor,
            Executor applyExecutor) {
        StateFactory<S> factory = (StateFactory<S>) rawFactory;
        return factory.create(barrier, resourceManager, listener, prepareExecutor, applyExecutor);
    }
}
