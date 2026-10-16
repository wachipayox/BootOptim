package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.optimization.client.DecocraftInventoryPatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;

/** Hands the optional Decocraft inventory overlay to the model reload; a no-op unless switched on. */
@Mixin(ModelManager.class)
abstract class ModelManagerDecocraftInventoryMixin {
    @WrapMethod(method = "reload(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/util/profiling/ProfilerFiller;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", require = 0)
    private CompletableFuture<Void> bootoptim$inventoryOverlay(
            PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepare,
            ProfilerFiller apply, Executor background, Executor game, Operation<CompletableFuture<Void>> original) {
        return original.call(barrier, DecocraftInventoryPatch.wrap(resources), prepare, apply, background, game);
    }
}
