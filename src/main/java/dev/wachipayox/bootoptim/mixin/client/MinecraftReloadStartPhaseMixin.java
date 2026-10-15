package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Startup-report mark at the first {@code Minecraft.reloadResourcePacks()} call, which ends the mod-loading
 * segment. Observation only; {@code require = 0} keeps the game starting if the method changes.
 */
@Mixin(Minecraft.class)
abstract class MinecraftReloadStartPhaseMixin {
    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), require = 0)
    private void bootoptim$firstReloadStart(CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        StartupProfiler.markFirstReloadStart();
    }
}
