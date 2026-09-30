package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.profiling.client.TrialBakeMetrics;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ModelBakery.class)
abstract class TrialBakeMetricsMixin {
    @WrapMethod(method = "bakeModels", require = 0)
    private void bootoptim$measureBake(ModelBakery.TextureGetter getter, Operation<Void> original) {
        TrialBakeMetrics.measure(() -> original.call(getter));
    }
}
