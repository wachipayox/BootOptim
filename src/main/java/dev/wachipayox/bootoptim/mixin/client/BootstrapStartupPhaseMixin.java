package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup-report marks around vanilla {@code Bootstrap.bootStrap()}. Observation only: it changes no
 * behaviour, and with startup reporting off the marker is two static checks. {@code require = 0}: if the
 * method is renamed, the marks are skipped and the game starts as before.
 */
@Mixin(Bootstrap.class)
abstract class BootstrapStartupPhaseMixin {
    @Inject(method = "bootStrap()V", at = @At("HEAD"), require = 0)
    private static void bootoptim$bootstrapStart(CallbackInfo callback) {
        StartupProfiler.markPhase("bootstrap_start");
    }

    @Inject(method = "bootStrap()V", at = @At("RETURN"), require = 0)
    private static void bootoptim$bootstrapEnd(CallbackInfo callback) {
        StartupProfiler.markPhase("bootstrap_end");
    }
}
