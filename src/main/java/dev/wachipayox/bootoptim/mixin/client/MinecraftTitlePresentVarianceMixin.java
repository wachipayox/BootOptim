package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import dev.wachipayox.bootoptim.profiling.client.ReloadListenerVarianceProfiler;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
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

    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V",
                    shift = At.Shift.AFTER))
    private void bootoptim$afterDisplayUpdate(boolean renderLevel, CallbackInfo ci) {
        if (!VarianceProbe.enabled()
                || !StartupProfiler.hasMainMenuOpened()
                || !BOOTOPTIM$PRESENT_REPORTED.compareAndSet(false, true)) {
            return;
        }
        VarianceProbe.point("main_menu_presented");
        ReloadListenerVarianceProfiler.emitAfterTitle();
        if (StartupProfiler.shouldExitAfterPresentedTitle()) {
            ((Minecraft) (Object) this).stop();
        }
    }
}
