package dev.wachipayox.bootoptim.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.equipment.wrench.IWrenchable",
        "com.simibubi.create.content.kinetics.base.IRotate",
        "com.simibubi.create.foundation.block.IBE"
}, remap = false)
interface ShapePrewarmInterfaceAuditMixin {}
