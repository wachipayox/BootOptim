package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.McefReloadOverlapCeiling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional, diagnostic-only MCEF 2.1.6 hook for the initial-reload overlap ceiling. */
@Pseudo
@Mixin(targets = "com.cinemamod.mcef.MCEF", remap = false)
abstract class McefInitializationOverlapMixin {
    @Inject(method = "initialize()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$delayAutomaticInitialize(CallbackInfoReturnable<Boolean> cir) {
        if (McefReloadOverlapCeiling.shouldSuppressInitialize()) {
            // CefInitMixin ignores the boolean result. Keep the automatic caller successful while
            // deliberately leaving MCEF's real initialized state untouched until the reload starts.
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "getApp()Lcom/cinemamod/mcef/MCEFApp;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeGetApp(CallbackInfoReturnable<?> cir) {
        McefReloadOverlapCeiling.beforeConsumer("getApp");
    }

    @Inject(
            method = "getClient()Lcom/cinemamod/mcef/MCEFClient;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeGetClient(CallbackInfoReturnable<?> cir) {
        McefReloadOverlapCeiling.beforeConsumer("getClient");
    }

    @Inject(
            method = "createBrowser(Ljava/lang/String;Z)Lcom/cinemamod/mcef/MCEFBrowser;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$forceBeforeCreateBrowser(
            String url,
            boolean transparent,
            CallbackInfoReturnable<?> cir) {
        McefReloadOverlapCeiling.beforeConsumer("createBrowser2");
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
        McefReloadOverlapCeiling.beforeConsumer("createBrowser4");
    }
}
