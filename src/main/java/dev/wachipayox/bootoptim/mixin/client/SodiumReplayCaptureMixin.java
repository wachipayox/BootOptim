package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.SodiumPhaseReplay;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags", remap = false, priority = 1100)
abstract class SodiumReplayCaptureMixin {
    @Inject(method = "getQuadFlags", at = @At("HEAD"), require = 0)
    private static void bootoptim$capture(@Coerce Object quad, Direction face, CallbackInfoReturnable<Integer> result) {
        SodiumPhaseReplay.capture(quad, face);
    }
}
