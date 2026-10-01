package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.SegmentOwnerTrial;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
abstract class SegmentOwnerReloadEndpointMixin {
    @Inject(method = "createReload", at = @At("RETURN"), require = 0)
    private void bootoptim$observe(CallbackInfoReturnable<ReloadInstance> result) {
        SegmentOwnerTrial.observe((ReloadableResourceManager) (Object) this, result.getReturnValue());
    }
}
