package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.McefFirstConsumerDeferCeiling;
import net.minecraft.client.gui.GuiGraphics;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FancyMenu BrowserElement is a special case: its normal afterConstruction() browser creation is
 * one-shot. If that call happened while CEF was deferred, retry it from the first visible render
 * after FancyMenu's own MCEF bridge becomes ready.
 */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.customization.element.elements.browser.BrowserElement", remap = false)
abstract class FancyMenuBrowserElementFirstConsumerMixin {
    @Unique
    private boolean bootoptim$retryBrowserConstruction;

    @Shadow
    public abstract void afterConstruction();

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(
                    value = "FIELD",
                    target = "Lde/keksuccino/fancymenu/customization/element/elements/browser/BrowserElement;browser:Lde/keksuccino/fancymenu/util/mcef/WrappedMCEFBrowser;",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0),
            require = 0)
    private void bootoptim$forceAndRetryVisibleBrowserElement(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci) {
        McefFirstConsumerDeferCeiling.beforeConsumer("fancymenu_browser_element");

        if (McefFirstConsumerDeferCeiling.isFancyMenuMcefBridgeReady()) {
            if (bootoptim$retryBrowserConstruction) {
                bootoptim$retryBrowserConstruction = false;
                afterConstruction();
            }
        } else {
            // The visible element has become a real consumer, but FancyMenu updates its own bridge
            // asynchronously after the real MCEF initializer. Remember to retry the one-shot setup.
            bootoptim$retryBrowserConstruction = true;
        }
    }
}
