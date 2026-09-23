package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.DecocraftModelArchiveBatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact 1.21.1 ModelManager callsites; all ineligible resources use stock. */
@Mixin(ModelManager.class)
abstract class ModelManagerDecocraftArchiveBatchMixin {
    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$beginArchiveGeneration(CallbackInfoReturnable<?> cir) {
        DecocraftModelArchiveBatch.beginReload();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$reportArchiveGeneration(CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> DecocraftModelArchiveBatch.finishReload(failure == null));
        }
    }

    @WrapOperation(method = "lambda$loadBlockModels$8",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;openAsReader()Ljava/io/BufferedReader;"),
            remap = false, require = 0)
    private static BufferedReader bootoptim$openModel(
            Resource resource, Operation<BufferedReader> original,
            Map.Entry<ResourceLocation, Resource> entry) throws IOException {
        return DecocraftModelArchiveBatch.open(resource, entry.getKey(), () -> original.call(resource));
    }

    @WrapOperation(method = "lambda$loadBlockStates$12",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;openAsReader()Ljava/io/BufferedReader;"),
            remap = false, require = 0)
    private static BufferedReader bootoptim$openState(
            Resource resource, Operation<BufferedReader> original,
            Map.Entry<ResourceLocation, List<Resource>> entry) throws IOException {
        return DecocraftModelArchiveBatch.open(resource, entry.getKey(), () -> original.call(resource));
    }
}
