package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FlywheelShaderSourcesProbe;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low-cardinality allPreparations/allDone markers used only by the Flywheel diagnostic property. */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceFlywheelProbeMixin<S> {
    @Shadow
    @Final
    protected CompletableFuture<Unit> allPreparations;

    @Shadow
    protected CompletableFuture<List<S>> allDone;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$observeFlywheelReload(CallbackInfo ci) {
        FlywheelShaderSourcesProbe.observeReloadInstance(allPreparations, allDone);
    }
}
