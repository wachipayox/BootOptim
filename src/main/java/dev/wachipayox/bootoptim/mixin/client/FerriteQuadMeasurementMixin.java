package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.MeasuredQuadSet;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Both measured arms have the exact same diagnostic subclass; production uses stock. */
@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.Deduplicator", remap = false)
abstract class FerriteQuadMeasurementMixin {
    @WrapOperation(method = "<clinit>", at = @At(value = "NEW",
            target = "(Lit/unimi/dsi/fastutil/Hash$Strategy;)Lit/unimi/dsi/fastutil/objects/ObjectOpenCustomHashSet;"), require = 0)
    private static ObjectOpenCustomHashSet<Object> bootoptim$measureOriginalTable(
            Hash.Strategy<Object> strategy, Operation<ObjectOpenCustomHashSet<Object>> original) {
        return TrialFeatureGate.ENABLED ? new MeasuredQuadSet<>(strategy) : original.call(strategy);
    }
}
