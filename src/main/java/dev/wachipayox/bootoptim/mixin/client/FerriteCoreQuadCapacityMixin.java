package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.FerriteCoreQuadCapacity;
import dev.wachipayox.bootoptim.profiling.client.MeasuredQuadSet;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve stock generation clearing; optionally retain bounded empty table storage. */
@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.Deduplicator$1", remap = false)
abstract class FerriteCoreQuadCapacityMixin {
    @Unique private int bootoptim$previousQuadCount;

    @WrapOperation(method = "apply(Lcom/mojang/datafixers/util/Unit;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenCustomHashSet;clear()V"), require = 0)
    private void bootoptim$rememberCapacity(ObjectOpenCustomHashSet<?> set, Operation<Void> original) {
        bootoptim$previousQuadCount = FerriteCoreQuadCapacity.enabled() ? set.size() : 0;
        original.call(set);
    }

    @WrapOperation(method = "apply(Lcom/mojang/datafixers/util/Unit;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenCustomHashSet;trim()Z"), require = 0)
    private boolean bootoptim$boundedEmptyStorage(ObjectOpenCustomHashSet<?> set, Operation<Boolean> original) {
        int previous = bootoptim$previousQuadCount;
        bootoptim$previousQuadCount = 0;
        if (!FerriteCoreQuadCapacity.enabled() || previous <= 0 || !set.isEmpty()
                || (set.getClass() != ObjectOpenCustomHashSet.class
                    && !(TrialFeatureGate.ENABLED && set.getClass() == MeasuredQuadSet.class))) {
            return original.call(set);
        }
        int retained = Math.min(previous, FerriteCoreQuadCapacity.MAX_EXPECTED);
        if (!set.trim(retained)) {
            FerriteCoreQuadCapacity.report(previous, retained, false);
            return original.call(set);
        }
        FerriteCoreQuadCapacity.report(previous, retained, true);
        return true;
    }
}
