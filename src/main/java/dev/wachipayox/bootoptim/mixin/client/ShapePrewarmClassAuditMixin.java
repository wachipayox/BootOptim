package dev.wachipayox.bootoptim.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
        "dev.simulated_team.simulated.index.SimBlockShapes",
        "dev.simulated_team.simulated.index.SimBlockShapes$Builder",
        "dev.propulsionteam.propulsionsimulated.registries.PropulsionShapes",
        "dev.propulsionteam.propulsionsimulated.registries.PropulsionShapes$ShapeBuilder",
        "com.drmangotea.tfmg.base.TFMGShapes",
        "com.drmangotea.tfmg.base.TFMGShapes$Builder",
        "com.mrh0.createaddition.blocks.alternator.AlternatorBlock",
        "com.mrh0.createaddition.shapes.CAShapes",
        "com.mrh0.createaddition.shapes.CAShapes$Builder",
        "com.simibubi.create.content.kinetics.base.DirectionalKineticBlock",
        "com.simibubi.create.content.kinetics.base.KineticBlock",
        "net.createmod.catnip.math.VoxelShaper",
        "net.createmod.catnip.math.VoxelShaper$DefaultRotationValues",
        "net.createmod.catnip.math.VoxelShaper$HorizontalRotationValues",
        "net.createmod.catnip.math.VecHelper",
        "net.createmod.catnip.data.Iterate"
}, remap = false)
abstract class ShapePrewarmClassAuditMixin {}
