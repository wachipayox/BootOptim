package dev.wachipayox.bootoptim.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
        "com.simibubi.create.AllShapes",
        "com.simibubi.create.AllShapes$Builder",
        "com.simibubi.create.content.logistics.chute.ChuteShapes",
        "com.simibubi.create.content.trains.track.TrackVoxelShapes",
        "malte0811.ferritecore.fastmap.BinaryFastMapKey",
        "malte0811.ferritecore.fastmap.CompactFastMapKey",
        "malte0811.ferritecore.fastmap.FastMap",
        "malte0811.ferritecore.fastmap.FastMapKey",
        "malte0811.ferritecore.fastmap.PropertyIndexer",
        "malte0811.ferritecore.fastmap.PropertyIndexer$1",
        "malte0811.ferritecore.fastmap.PropertyIndexer$BoolIndexer",
        "malte0811.ferritecore.fastmap.PropertyIndexer$EnumIndexer",
        "malte0811.ferritecore.fastmap.PropertyIndexer$GenericIndexer",
        "malte0811.ferritecore.fastmap.PropertyIndexer$IntIndexer",
        "malte0811.ferritecore.fastmap.PropertyIndexer$WeirdVanillaDirectionIndexer",
        "malte0811.ferritecore.impl.FastMapEntryMap",
        "malte0811.ferritecore.impl.StateHolderImpl",
        "net.minecraft.world.level.block.Block",
        "net.minecraft.world.level.block.piston.PistonHeadBlock",
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
        "net.minecraft.world.level.block.state.BlockState",
        "net.minecraft.world.level.block.state.StateDefinition",
        "net.minecraft.world.level.block.state.StateHolder",
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
