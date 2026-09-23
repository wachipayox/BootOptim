package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DecocraftSpriteArchiveBatch;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the stock ModelManager future to bound each sprite input generation. */
@Mixin(ModelManager.class)
abstract class ModelManagerDecocraftSpriteBatchLifecycleMixin {
    @Inject(method = "reload", at = @At("HEAD"))
    private void bootoptim$beginSpriteArchiveGeneration(CallbackInfoReturnable<?> cir) {
        DecocraftSpriteArchiveBatch.beginReload();
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void bootoptim$reportSpriteArchiveGeneration(CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() instanceof CompletableFuture<?> future) {
            DecocraftSpriteArchiveBatch.attachFinish(future);
        } else {
            DecocraftSpriteArchiveBatch.attachFinish(null);
        }
    }
}
