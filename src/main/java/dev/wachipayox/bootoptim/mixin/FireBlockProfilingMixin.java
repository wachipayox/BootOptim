package dev.wachipayox.bootoptim.mixin;

import dev.wachipayox.bootoptim.profiling.BootstrapPhaseProfiler;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
abstract class FireBlockProfilingMixin {
    @Inject(method = "bootStrap", at = @At("HEAD"))
    private static void bootoptim$fireBootstrapStart(CallbackInfo ci) {
        BootstrapPhaseProfiler.begin("fire_bootstrap");
    }

    @Inject(method = "bootStrap", at = @At("RETURN"))
    private static void bootoptim$fireBootstrapEnd(CallbackInfo ci) {
        BootstrapPhaseProfiler.end("fire_bootstrap");
    }
}
