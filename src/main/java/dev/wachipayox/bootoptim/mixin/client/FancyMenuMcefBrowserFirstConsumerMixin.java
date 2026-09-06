package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.compat.client.McefFirstConsumerDefer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional FancyMenu browser background/overlay compatibility for first-consumer MCEF deferral. */
@Pseudo
@Mixin(
        targets = {
            "de.keksuccino.fancymenu.customization.background.backgrounds.browser.BrowserMenuBackground",
            "de.keksuccino.fancymenu.customization.decorationoverlay.overlays.browser.BrowserDecorationOverlay"
        },
        remap = false)
abstract class FancyMenuMcefBrowserFirstConsumerMixin {
    @Inject(method = "ensureBrowserCreated()V", at = @At("HEAD"), require = 0)
    private void bootoptim$forceBeforeBrowserCreationCheck(CallbackInfo ci) {
        McefFirstConsumerDefer.beforeConsumer("fancymenu_browser_container");
    }
}
