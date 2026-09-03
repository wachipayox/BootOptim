package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.compat.client.McefFirstConsumerDefer;
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
 * FancyMenu BrowserElement constructs its browser once. If that attempt occurred before CEF was
 * needed, retry its original construction path after the real FancyMenu/MCEF bridge becomes ready.
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
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        McefFirstConsumerDefer.beforeConsumer("fancymenu_browser_element");

        if (McefFirstConsumerDefer.isFancyMenuMcefBridgeReady()) {
            if (bootoptim$retryBrowserConstruction) {
                bootoptim$retryBrowserConstruction = false;
                afterConstruction();
            }
        } else {
            bootoptim$retryBrowserConstruction = true;
        }
    }
}
