package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelBakeryPrepareAttribution;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only hooks for Forgified Fabric Model Loading API's constructor-time work.
 * The target is optional and all hooks fail open when FFAPI is absent or its shape changes.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.model.loading.ModelLoadingEventDispatcher", remap = false)
abstract class FabricModelLoadingPrepareAttributionMixin {
    @Unique private long bootoptim$pluginInitStarted;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/fabricmc/fabric/impl/client/model/loading/ModelLoadingPluginContextImpl;<init>()V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void bootoptim$pluginInitStartAfterContextCreation(CallbackInfo ci) {
        this.bootoptim$pluginInitStarted = ModelBakeryPrepareAttribution.start();
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bootoptim$pluginInitReturn(CallbackInfo ci) {
        ModelBakeryPrepareAttribution.recordFabricPluginInit(this.bootoptim$pluginInitStarted);
        this.bootoptim$pluginInitStarted = 0L;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "addExtraModels",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),
            remap = false)
    private void bootoptim$profileExtraModel(Consumer consumer, Object id) {
        long started = ModelBakeryPrepareAttribution.start();
        try {
            consumer.accept(id);
        } finally {
            if (id instanceof ResourceLocation location) {
                ModelBakeryPrepareAttribution.recordFabricExtraModel(location, started);
            }
        }
    }
}
