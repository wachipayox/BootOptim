package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import dev.wachipayox.bootoptim.profiling.client.ReloadListenerVarianceProfiler;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the first Window.updateDisplay completion after TitleScreen opening.
 *
 * <p>The exact pack may replace TitleScreen later in the same tick (for example with a first-run welcome
 * screen), so checking Minecraft.screen at runTick RETURN can miss a frame that was already presented.
 * This probe is armed by StartupProfiler.markMainMenu() and consumes that state at the actual display-update
 * boundary instead. No render or swap call is redirected or reordered.</p>
 */
@Mixin(Minecraft.class)
abstract class MinecraftTitlePresentVarianceMixin {
    private static final AtomicBoolean BOOTOPTIM$PRESENT_REPORTED = new AtomicBoolean();
    @Unique private boolean bootoptim$firstTitleFrame;
    @Unique private VarianceProbe.Stamp bootoptim$renderStamp;
    @Unique private VarianceProbe.Stamp bootoptim$blitStamp;
    @Unique private VarianceProbe.Stamp bootoptim$displayStamp;

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void bootoptim$beforeTitleRender(boolean renderLevel, CallbackInfo ci) {
        if (!VarianceProbe.enabled() || !StartupProfiler.hasMainMenuOpened() || BOOTOPTIM$PRESENT_REPORTED.get()) {
            return;
        }
        bootoptim$firstTitleFrame = true;
        bootoptim$renderStamp = VarianceProbe.start("title_first_frame_render");
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
    private void bootoptim$afterTitleRender(boolean renderLevel, CallbackInfo ci) {
        if (!VarianceProbe.enabled() || !StartupProfiler.hasMainMenuOpened() || BOOTOPTIM$PRESENT_REPORTED.get()) {
            return;
        }
        if (bootoptim$renderStamp != null) {
            VarianceProbe.finish("title_first_frame_render", bootoptim$renderStamp);
            bootoptim$renderStamp = null;
        }
        // TitleScreen can open inside GameRenderer.render. In that case the render
        // entry predates opening, so no full render scope can be claimed.
        bootoptim$firstTitleFrame = true;
        VarianceProbe.point("title_first_frame_render_return");
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void bootoptim$beforeTitleBlit(boolean renderLevel, CallbackInfo ci) {
        if (bootoptim$firstTitleFrame) {
            bootoptim$blitStamp = VarianceProbe.start("title_first_frame_blit");
        }
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V", shift = At.Shift.AFTER))
    private void bootoptim$afterTitleBlit(boolean renderLevel, CallbackInfo ci) {
        if (bootoptim$firstTitleFrame) {
            VarianceProbe.finish("title_first_frame_blit", bootoptim$blitStamp);
            bootoptim$blitStamp = null;
        }
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void bootoptim$beforeDisplayUpdate(boolean renderLevel, CallbackInfo ci) {
        if (bootoptim$firstTitleFrame) {
            bootoptim$displayStamp = VarianceProbe.start("title_first_frame_display_update");
        }
    }

    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V",
                    shift = At.Shift.AFTER))
    private void bootoptim$afterDisplayUpdate(boolean renderLevel, CallbackInfo ci) {
        if (bootoptim$firstTitleFrame) {
            VarianceProbe.finish("title_first_frame_display_update", bootoptim$displayStamp);
            bootoptim$displayStamp = null;
            bootoptim$firstTitleFrame = false;
        }
        if (!VarianceProbe.enabled()
                || !StartupProfiler.hasMainMenuOpened()
                || !BOOTOPTIM$PRESENT_REPORTED.compareAndSet(false, true)) {
            return;
        }
        VarianceProbe.point("main_menu_presented");
        Minecraft game = (Minecraft) (Object) this;
        VarianceProbe.point("startup_presented_screen", game.screen == null ? "none" : game.screen.getClass().getName());
        ReloadListenerVarianceProfiler.emitAfterTitle();
        if (StartupProfiler.shouldExitAfterPresentedTitle()) {
            ((Minecraft) (Object) this).stop();
        }
    }
}
