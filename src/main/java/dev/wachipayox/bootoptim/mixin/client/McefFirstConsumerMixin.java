package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.McefFirstConsumerCeiling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional MCEF 2.1.6 hooks for the first-consumer ceiling experiment. */
@Pseudo
@Mixin(targets = "com.cinemamod.mcef.MCEF", remap = false)
abstract class McefFirstConsumerMixin {
    @Inject(method = "initialize()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$deferAutomaticInitialize(CallbackInfoReturnable<Boolean> cir) {
        if (McefFirstConsumerCeiling.shouldSuppressAutomaticInitialize()) {
            // MCEF's CefInitMixin does not inspect the boolean. Keep its already-scheduled automatic
            // task successful while deliberately leaving MCEF's real state uninitialized.
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "getApp()Lcom/cinemamod/mcef/MCEFApp;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeGetApp(CallbackInfoReturnable<?> cir) {
        McefFirstConsumerCeiling.beforeConsumer("getApp");
    }

    @Inject(
            method = "getClient()Lcom/cinemamod/mcef/MCEFClient;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeGetClient(CallbackInfoReturnable<?> cir) {
        McefFirstConsumerCeiling.beforeConsumer("getClient");
    }

    @Inject(
            method = "createBrowser(Ljava/lang/String;Z)Lcom/cinemamod/mcef/MCEFBrowser;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeCreateBrowser(
            String url,
            boolean transparent,
            CallbackInfoReturnable<?> cir) {
        McefFirstConsumerCeiling.beforeConsumer("createBrowser2");
    }

    @Inject(
            method = "createBrowser(Ljava/lang/String;ZII)Lcom/cinemamod/mcef/MCEFBrowser;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeCreateSizedBrowser(
            String url,
            boolean transparent,
            int width,
            int height,
            CallbackInfoReturnable<?> cir) {
        McefFirstConsumerCeiling.beforeConsumer("createBrowser4");
    }
}
