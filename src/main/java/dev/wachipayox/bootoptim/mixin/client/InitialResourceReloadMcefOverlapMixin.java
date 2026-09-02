package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.McefReloadOverlapCeiling;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Completes the diagnostic MCEF deferral only after stock reload preparation has been scheduled. */
@Mixin(ReloadableResourceManager.class)
abstract class InitialResourceReloadMcefOverlapMixin {
    @Inject(
            method = "createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;",
            at = @At("RETURN"),
            require = 1)
    private void bootoptim$initializeMcefAfterReloadKickoff(CallbackInfoReturnable<ReloadInstance> cir) {
        McefReloadOverlapCeiling.afterResourceReloadStarted();
    }
}
