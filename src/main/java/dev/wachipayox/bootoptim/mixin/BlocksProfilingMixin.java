package dev.wachipayox.bootoptim.mixin;

import dev.wachipayox.bootoptim.profiling.BootstrapPhaseProfiler;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Blocks.class)
abstract class BlocksProfilingMixin {
    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void bootoptim$blocksStart(CallbackInfo ci) {
        BootstrapPhaseProfiler.begin("blocks_clinit");
    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void bootoptim$blocksEnd(CallbackInfo ci) {
        BootstrapPhaseProfiler.end("blocks_clinit");
    }
}
