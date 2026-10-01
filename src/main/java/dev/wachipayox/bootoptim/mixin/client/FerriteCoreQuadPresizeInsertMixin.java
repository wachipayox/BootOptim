package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.FerriteCoreQuadPresize;
import dev.wachipayox.bootoptim.profiling.client.MeasuredQuadSet;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.Deduplicator", remap = false)
abstract class FerriteCoreQuadPresizeInsertMixin {
    @WrapOperation(method = "deduplicate(Lnet/minecraft/client/renderer/block/model/BakedQuad;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenCustomHashSet;addOrGet(Ljava/lang/Object;)Ljava/lang/Object;"), require = 0)
    private static Object bootoptim$reserveAtFirstInsert(ObjectOpenCustomHashSet<Object> set, Object vertices,
                                                        Operation<Object> original) {
        MeasuredQuadSet<?> measured = TrialFeatureGate.ENABLED && set.getClass() == MeasuredQuadSet.class
                ? (MeasuredQuadSet<?>) set : null;
        if (measured == null || !MeasuredQuadSet.INSERTION_TIMING) {
            if (measured != null) measured.countInsertion();
            FerriteCoreQuadPresize.beforeInsert(set);
            return original.call(set, vertices);
        }
        long cpu = measured.startInsertionCpu(), wall = System.nanoTime();
        try {
            FerriteCoreQuadPresize.beforeInsert(set);
            return original.call(set, vertices);
        } finally { measured.endInsertion(cpu, wall); }
    }
}
