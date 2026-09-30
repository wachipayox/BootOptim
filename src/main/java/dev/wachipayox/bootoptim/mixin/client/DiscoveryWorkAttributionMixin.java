package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler.Phase;
import java.util.Collection;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Attribute the existing discovery callback without replacing cache/loading semantics. */
@Mixin(ModelBakery.class)
abstract class DiscoveryWorkAttributionMixin {
    @WrapOperation(method = "registerModelAndLoadDependencies", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/UnbakedModel;getDependencies()Ljava/util/Collection;"), require = 0)
    private Collection<ResourceLocation> bootoptim$dependencies(UnbakedModel model, Operation<Collection<ResourceLocation>> original) {
        return ModelGroupingProfiler.dependencies(model, () -> original.call(model));
    }

    @WrapOperation(method = "registerModelAndLoadDependencies", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/ModelBakery;getModel(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/resources/model/UnbakedModel;"), require = 0)
    private UnbakedModel bootoptim$lookup(ModelBakery bakery, ResourceLocation location, Operation<UnbakedModel> original) {
        return ModelGroupingProfiler.discoveryPhase(Phase.MODEL_LOOKUP, () -> original.call(bakery, location));
    }

    @WrapOperation(method = "registerModelAndLoadDependencies", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/ModelBakery;registerModel(Lnet/minecraft/client/resources/model/ModelResourceLocation;Lnet/minecraft/client/resources/model/UnbakedModel;)V"), require = 0)
    private void bootoptim$registration(ModelBakery bakery, ModelResourceLocation location, UnbakedModel model, Operation<Void> original) {
        ModelGroupingProfiler.discoveryPhase(Phase.TOP_LEVEL_REGISTRATION, () -> original.call(bakery, location, model));
    }
}
