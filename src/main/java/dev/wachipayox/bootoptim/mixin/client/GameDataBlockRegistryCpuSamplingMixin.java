package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import dev.wachipayox.bootoptim.profiling.client.RegistryBlockCpuSampler;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only observer around the stock per-registry dispatch call in GameData.postRegisterEvents().
 *
 * <p>This reuses the exact priority-1100 boundary validated by PR #99. BootOptim does not redirect, invoke,
 * wrap, replace, reorder or parallelize the event bus. ModernFix's lower-priority redirect remains authoritative.</p>
 */
@Mixin(value = GameData.class, priority = 1100)
abstract class GameDataBlockRegistryCpuSamplingMixin {
    @Inject(
            method = "postRegisterEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$beforeRegisterEventDispatch(CallbackInfo ci, @Local RegisterEvent registerEvent) {
        RegistryBlockCpuSampler.begin(registerEvent);
    }

    @Inject(
            method = "postRegisterEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.AFTER,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$afterRegisterEventDispatch(CallbackInfo ci, @Local RegisterEvent registerEvent) {
        RegistryBlockCpuSampler.end(registerEvent);
    }
}
