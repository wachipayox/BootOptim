package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.MaterialResolutionListPool;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

import java.util.ArrayList;

/** Opt-in, reload-local allocation experiment; vanilla remains the default. */
@Mixin(BlockModel.class)
abstract class BlockModelMaterialResolutionListPoolMixin {
    @Inject(method = "getMaterial", at = @At("HEAD"), require = 0)
    private void bootoptim$beginMaterialResolution(String texture, CallbackInfoReturnable<Material> cir) {
        MaterialResolutionListPool.begin();
    }

    @Inject(method = "getMaterial", at = @At("RETURN"), require = 0)
    private void bootoptim$endMaterialResolution(String texture, CallbackInfoReturnable<Material> cir) {
        MaterialResolutionListPool.end();
    }

    @Redirect(
            method = "getMaterial",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"),
            require = 0)
    private ArrayList<String> bootoptim$borrowMaterialResolutionList() {
        return MaterialResolutionListPool.borrow();
    }
}
