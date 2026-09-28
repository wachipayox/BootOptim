package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.compat.client.McefFirstConsumerDefer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only MCEF's redundant delayed auto-init task while first-consumer defer is armed. */
@Mixin(BlockableEventLoop.class)
abstract class McefScreenInitTaskMixin {
    @Inject(method = "execute(Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$skipDeferredMcefScreenTask(Runnable task, CallbackInfo ci) {
        if (McefFirstConsumerDefer.shouldSkipAutomaticScreenInitTask()) {
            ci.cancel();
        }
    }
}
