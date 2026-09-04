package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import net.neoforged.neoforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only observer for ModernFix's existing per-ModContainer registry dispatch lambda.
 *
 * <p>ModernFix owns the dispatch. This mixin has higher priority only so Mixin permits an injection into a method
 * merged by ModernFix priority 1000. It inserts nanoTime accounting immediately before/after the existing
 * ModContainer.acceptEvent invocation and never invokes, redirects, wraps, replaces or reorders that call.</p>
 */
@Mixin(value = GameData.class, priority = 1100)
abstract class ModernFixRegistryEventProgressTimingMixin {
    @Inject(
            method = "lambda$postWithProgressBar$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModContainer;acceptEvent(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$beforeExistingContainerPost(CallbackInfo ci) {
        FmlRegistryProfiler.beginActiveModContainerPost();
    }

    @Inject(
            method = "lambda$postWithProgressBar$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModContainer;acceptEvent(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.AFTER,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$afterExistingContainerPost(CallbackInfo ci) {
        FmlRegistryProfiler.endActiveModContainerPost();
    }
}
