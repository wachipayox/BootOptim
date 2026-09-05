package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.ModernFixReloadParallelismLease;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Property-gated experiment that temporarily reduces only the exact ModernFix dedicated
 * preparation pool used by the first client startup resource reload.
 */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceModernFixLeaseMixin<S> {
    @Shadow
    protected CompletableFuture<List<S>> allDone;

    @Unique
    private ModernFixReloadParallelismLease.Lease bootoptim$modernFixReloadLease;

    @Inject(method = "<init>", at = @At("HEAD"), require = 0)
    private void bootoptim$acquireModernFixReloadLease(
            Executor prepareExecutor,
            Executor applyExecutor,
            ResourceManager resourceManager,
            List<PreparableReloadListener> listeners,
            @Coerce Object stateFactory,
            CompletableFuture<Unit> initialStage,
            CallbackInfo ci) {
        bootoptim$modernFixReloadLease = ModernFixReloadParallelismLease.tryAcquire(prepareExecutor);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$observeModernFixReloadCompletion(
            Executor prepareExecutor,
            Executor applyExecutor,
            ResourceManager resourceManager,
            List<PreparableReloadListener> listeners,
            @Coerce Object stateFactory,
            CompletableFuture<Unit> initialStage,
            CallbackInfo ci) {
        ModernFixReloadParallelismLease.Lease lease = bootoptim$modernFixReloadLease;
        bootoptim$modernFixReloadLease = null;
        if (lease == null) {
            return;
        }

        CompletableFuture<List<S>> completion = allDone;
        if (completion == null) {
            lease.close(new IllegalStateException("SimpleReloadInstance allDone was null"), false);
            return;
        }
        completion.whenComplete((ignored, failure) -> lease.close(failure, completion.isCancelled()));
    }
}
