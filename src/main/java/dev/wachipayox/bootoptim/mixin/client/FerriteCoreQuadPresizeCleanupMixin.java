package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.FerriteCoreQuadPresize;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.Deduplicator$1", remap = false)
abstract class FerriteCoreQuadPresizeCleanupMixin {
    @WrapOperation(method = "apply(Lcom/mojang/datafixers/util/Unit;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenCustomHashSet;clear()V"), require = 0)
    private void bootoptim$rememberSize(ObjectOpenCustomHashSet<?> set, Operation<Void> original) {
        int previous = set.size();
        original.call(set);
        FerriteCoreQuadPresize.remember(set, previous, FerriteCoreQuadPresize.enabled());
    }
}
