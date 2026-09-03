package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.McefFirstConsumerDeferCeiling;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional FancyMenu 3.9.0-wedit compatibility for MCEF-backed video consumers.
 *
 * <p>Both video implementations return from render before touching MCEF.getClient/createBrowser when
 * FancyMenu's own MCEF_initialized bridge flag is false. Inject at that exact field read so only a
 * video which is actually being rendered becomes a first consumer.</p>
 */
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
        McefFirstConsumerDeferCeiling.beforeConsumer("fancymenu_video_render");
    }
}
