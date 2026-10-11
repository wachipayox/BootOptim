package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.client.renderer.block.model.BlockModel;
import org.spongepowered.asm.mixin.Mixin;

/** Select the target for the guarded final operation extension; no method injection. */
@Mixin(BlockModel.class)
abstract class BlockModelCycleSetAnchorMixin {}
