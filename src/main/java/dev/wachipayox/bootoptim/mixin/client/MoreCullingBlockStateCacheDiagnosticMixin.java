package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.MoreCullingStartupDiagnostics;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes MoreCulling's per-BlockState culling-shape cache rebuild without changing it. */
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 900)
abstract class MoreCullingBlockStateCacheDiagnosticMixin {
    @Inject(method = "moreculling$initShapeCache", at = @At("HEAD"), remap = false, require = 0)
    private void bootoptim$moreCullingShapeCacheStart(CallbackInfo ci) {
        MoreCullingStartupDiagnostics.onShapeCacheStateStart((BlockState) (Object) this);
    }

    @Inject(method = "moreculling$initShapeCache", at = @At("RETURN"), remap = false, require = 0)
    private void bootoptim$moreCullingShapeCacheEnd(CallbackInfo ci) {
        MoreCullingStartupDiagnostics.onShapeCacheStateEnd();
    }
}
