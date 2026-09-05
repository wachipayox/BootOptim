package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.compat.client.McefFirstConsumerDefer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional MCEF 2.1.6 hooks for first-consumer CEF deferral. */
@Pseudo
@Mixin(targets = "com.cinemamod.mcef.MCEF", remap = false)
abstract class McefFirstConsumerDeferMixin {
    @Inject(method = "initialize()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$deferAutomaticInitialize(CallbackInfoReturnable<Boolean> cir) {
        if (McefFirstConsumerDefer.shouldSuppressInitialize()) {
            // CefInitMixin ignores this boolean; the real MCEF initialized state remains false.
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getApp()Lcom/cinemamod/mcef/MCEFApp;", at = @At("HEAD"), require = 0)
    private static void bootoptim$forceBeforeGetApp(CallbackInfoReturnable<?> cir) {
        McefFirstConsumerDefer.beforeConsumer("getApp");
    }

    @Inject(method = "getClient()Lcom/cinemamod/mcef/MCEFClient;", at = @At("HEAD"), require = 0)
    private static void bootoptim$forceBeforeGetClient(CallbackInfoReturnable<?> cir) {
        McefFirstConsumerDefer.beforeConsumer("getClient");
    }

    @Inject(method = "createBrowser(Ljava/lang/String;Z)Lcom/cinemamod/mcef/MCEFBrowser;", at = @At("HEAD"), require = 0)
    private static void bootoptim$forceBeforeCreateBrowser(String url, boolean transparent, CallbackInfoReturnable<?> cir) {
        McefFirstConsumerDefer.beforeConsumer("createBrowser2");
    }

    @Inject(method = "createBrowser(Ljava/lang/String;ZII)Lcom/cinemamod/mcef/MCEFBrowser;", at = @At("HEAD"), require = 0)
    private static void bootoptim$forceBeforeCreateSizedBrowser(
            String url, boolean transparent, int width, int height, CallbackInfoReturnable<?> cir) {
        McefFirstConsumerDefer.beforeConsumer("createBrowser4");
    }
}
