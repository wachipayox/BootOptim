package dev.wachipayox.bootoptim.mixin.client;

import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PathPackResources.class)
public interface PathPackResourcesRootAccessor {
    @Accessor("root")
    Path bootoptim$getRoot();
}
