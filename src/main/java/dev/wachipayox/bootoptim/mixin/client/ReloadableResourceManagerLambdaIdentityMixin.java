package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.MinecraftReloadLambdaIdentityProfiler;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Diagnostic-only registration/callsite identity probe; listener behavior is never wrapped. */
@Mixin(ReloadableResourceManager.class)
abstract class ReloadableResourceManagerLambdaIdentityMixin {
    @Shadow
    private List<PreparableReloadListener> listeners;

    @Inject(method = "registerReloadListener", at = @At("HEAD"), require = 0)
    private void bootoptim$captureMinecraftLambdaRegistration(
            PreparableReloadListener listener,
            CallbackInfo ci) {
        MinecraftReloadLambdaIdentityProfiler.onRegister(listener);
    }

    @Inject(method = "createReload", at = @At("HEAD"), require = 0)
    private void bootoptim$emitFinalOrderedMinecraftLambdas(
            Executor prepareExecutor,
            Executor applyExecutor,
            CompletableFuture<Unit> initialStage,
            List<PackResources> packs,
            CallbackInfoReturnable<ReloadInstance> cir) {
        if (!MinecraftReloadLambdaIdentityProfiler.enabled()) {
            return;
        }
        MinecraftReloadLambdaIdentityProfiler.onCreateReload(listeners);
    }
}
