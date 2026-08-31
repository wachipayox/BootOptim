package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Switches Minecraft's built-in resource-reload profiler on only while BootOptim startup
 * profiling is enabled. The profiled reload implementation preserves listener execution
 * semantics while reporting per-listener preparation/apply timings at INFO level.
 */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceProfilingMixin {
    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
    private static boolean bootoptim$enableProfiledReload(boolean profiled) {
        return profiled || StartupProfiler.isEnabled();
    }
}
