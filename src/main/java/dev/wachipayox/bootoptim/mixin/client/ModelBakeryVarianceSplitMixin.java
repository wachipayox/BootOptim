package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.*;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Coarse disjoint #217/#221 constructor scopes; no per-model clocks. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryVarianceSplitMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadAllBlockStates()V"))
    private void bootoptim$blockstates(BlockStateModelLoader loader) {
        var stamp = VarianceProbe.start("bakery_blockstate_registration");
        try { loader.loadAllBlockStates(); }
        finally { VarianceProbe.finish("bakery_blockstate_registration", stamp); }
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void bootoptim$parents(Collection<UnbakedModel> models, Consumer<UnbakedModel> resolver) {
        var stamp = VarianceProbe.start("bakery_parent_resolution");
        try { models.forEach(resolver); }
        finally { VarianceProbe.finish("bakery_parent_resolution", stamp); }
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onRegisterAdditionalModels(Ljava/util/Set;)V", remap = false))
    private void bootoptim$additional(Set<ModelResourceLocation> models) {
        var stamp = VarianceProbe.start("bakery_additional_model_event");
        try { ClientHooks.onRegisterAdditionalModels(models); }
        finally { VarianceProbe.finish("bakery_additional_model_event", stamp); }
    }
}
