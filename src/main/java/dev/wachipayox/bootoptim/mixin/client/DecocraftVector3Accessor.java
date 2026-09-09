package dev.wachipayox.bootoptim.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.razz.decocraft.models.libgdx.Vector3", remap = false)
interface DecocraftVector3Accessor {
    @Accessor("x") float bootoptim$getX();
    @Accessor("x") void bootoptim$setX(float value);
    @Accessor("y") float bootoptim$getY();
    @Accessor("y") void bootoptim$setY(float value);
    @Accessor("z") float bootoptim$getZ();
    @Accessor("z") void bootoptim$setZ(float value);
}
