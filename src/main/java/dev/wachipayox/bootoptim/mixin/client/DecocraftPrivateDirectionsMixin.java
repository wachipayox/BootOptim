package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.DecocraftPrivateDirections;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Private read-only direction array for Decocraft's facing loop; see {@link DecocraftPrivateDirections}. */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", remap = false)
abstract class DecocraftPrivateDirectionsMixin {
    @WrapOperation(
            method = "calculateFacing([Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery$VertexData;)Lnet/minecraft/core/Direction;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"),
            require = 0)
    private Direction[] bootoptim$privateDirections(Operation<Direction[]> original) {
        return DecocraftPrivateDirections.directions(original);
    }
}
