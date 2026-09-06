package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the first completed client tick after a TitleScreen frame has gone through Window.updateDisplay.
 * No render or swap call is redirected or reordered.
 */
@Mixin(Minecraft.class)
abstract class MinecraftTitlePresentVarianceMixin {
    private static final AtomicBoolean BOOTOPTIM$PRESENT_REPORTED = new AtomicBoolean();

    @Inject(method = "runTick", at = @At("RETURN"))
    private void bootoptim$afterPresentedTitleTick(boolean renderLevel, CallbackInfo ci) {
        if (!VarianceProbe.enabled() || BOOTOPTIM$PRESENT_REPORTED.get()) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.screen instanceof TitleScreen && BOOTOPTIM$PRESENT_REPORTED.compareAndSet(false, true)) {
            VarianceProbe.point("main_menu_presented");
            if (StartupProfiler.shouldExitAfterPresentedTitle()) {
                minecraft.stop();
            }
        }
    }
}
