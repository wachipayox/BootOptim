package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.QuadCoordinateView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView", remap = false)
public interface SodiumQuadCoordinateBridgeMixin extends QuadCoordinateView {}
