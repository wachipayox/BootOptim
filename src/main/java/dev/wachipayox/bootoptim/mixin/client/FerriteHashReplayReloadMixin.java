package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.FerriteHashReplay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public abstract class FerriteHashReplayReloadMixin {
    @Inject(method = "createReload", at = @At("RETURN"), require = 0)
    private void bootoptim$observe(CallbackInfoReturnable<ReloadInstance> result) {
        FerriteHashReplay.observe((ReloadableResourceManager) (Object) this, result.getReturnValue());
    }
}
