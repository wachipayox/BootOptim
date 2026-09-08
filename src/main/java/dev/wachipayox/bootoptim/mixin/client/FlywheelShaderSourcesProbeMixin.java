package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FlywheelShaderSourcesProbe;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the one stock ShaderSources construction without creating another instance. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.glsl.ShaderSources", remap = false)
abstract class FlywheelShaderSourcesProbeMixin {
    @Inject(method = "<init>", at = @At("HEAD"), require = 0)
    private void bootoptim$sourcesStart(ResourceManager manager, CallbackInfo ci) {
        FlywheelShaderSourcesProbe.sourcesPrepareStart(manager);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$sourcesEnd(ResourceManager manager, CallbackInfo ci) {
        FlywheelShaderSourcesProbe.sourcesPrepareEnd(manager);
    }
}
