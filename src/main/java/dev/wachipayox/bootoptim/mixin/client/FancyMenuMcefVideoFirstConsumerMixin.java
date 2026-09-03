package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.compat.client.McefFirstConsumerDefer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional FancyMenu 3.9.0-wedit compatibility for MCEF-backed video consumers. */
@Pseudo
@Mixin(
        targets = {
            "de.keksuccino.fancymenu.customization.background.backgrounds.video.mcef.MCEFVideoMenuBackground",
            "de.keksuccino.fancymenu.customization.element.elements.video.mcef.MCEFVideoElement"
        },
        remap = false)
abstract class FancyMenuMcefVideoFirstConsumerMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(
                    value = "FIELD",
                    target = "Lde/keksuccino/fancymenu/util/mcef/MCEFUtil;MCEF_initialized:Z",
                    opcode = Opcodes.GETSTATIC),
            require = 0)
    private void bootoptim$forceBeforeMcefVideoRenderCheck(CallbackInfo ci) {
        McefFirstConsumerDefer.beforeConsumer("fancymenu_video_render");
    }
}
