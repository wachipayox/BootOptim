package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DecocraftJarReadAheadCeiling;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Runs the Decocraft physical-JAR read-ahead ceiling immediately before the initial resource reload. */
@Mixin(Minecraft.class)
public abstract class MinecraftInitialResourceReloadReadAheadMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"),
            index = 0,
            require = 1)
    private Executor bootoptim$warmDecocraftJarBeforeInitialResourceReload(Executor preparationExecutor) {
        DecocraftJarReadAheadCeiling.runBeforeInitialResourceReload();
        return preparationExecutor;
    }
}
