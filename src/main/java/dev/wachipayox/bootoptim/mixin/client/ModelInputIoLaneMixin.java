package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.ModelInputIoLane;
import java.io.BufferedReader;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Narrow 1.21.1 experiment; original Resource.openAsReader runs exactly once. */
@Mixin(ModelManager.class)
abstract class ModelInputIoLaneMixin {
    @Unique private ModelInputIoLane.Trace bootoptim$ioLaneTrace;

    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$ioLaneBegin(CallbackInfoReturnable<?> cir) {
        bootoptim$ioLaneTrace = ModelInputIoLane.beginReload();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$ioLaneObserve(CallbackInfoReturnable<?> cir) {
        ModelInputIoLane.Trace trace = bootoptim$ioLaneTrace;
        bootoptim$ioLaneTrace = null;
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            ModelInputIoLane.observeReload(trace, future);
        } else {
            ModelInputIoLane.observeReload(trace, null);
        }
    }

    @WrapOperation(method = {"lambda$loadBlockModels$8", "lambda$loadBlockStates$12"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;openAsReader()Ljava/io/BufferedReader;"),
            remap = false)
    private static BufferedReader bootoptim$boundedOpen(Resource source, Operation<BufferedReader> original) {
        return ModelInputIoLane.open(source, original);
    }
}
