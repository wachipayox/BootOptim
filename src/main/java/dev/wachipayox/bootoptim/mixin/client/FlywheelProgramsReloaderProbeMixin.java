package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FlywheelShaderSourcesProbe;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only marker around Flywheel's existing synchronous commit. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.FlwProgramsReloader", remap = false)
abstract class FlywheelProgramsReloaderProbeMixin {
    @Unique
    private long bootoptim$flywheelGeneration;

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), require = 0)
    private void bootoptim$commitStart(ResourceManager manager, CallbackInfo ci) {
        bootoptim$flywheelGeneration = FlywheelShaderSourcesProbe.beginCommit(manager);
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 0)
    private void bootoptim$commitEnd(ResourceManager manager, CallbackInfo ci) {
        FlywheelShaderSourcesProbe.endCommit(manager, bootoptim$flywheelGeneration);
        bootoptim$flywheelGeneration = 0L;
    }
}
