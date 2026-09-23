package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.*;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Coarse disjoint #217/#221 constructor scopes; no per-model clocks. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryVarianceSplitMixin {
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$AFTER_BLOCKSTATES = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$VANILLA_ITEMS = new ThreadLocal<>();
    @Unique private static final ThreadLocal<VarianceProbe.Stamp> BOOTOPTIM$ADDITIONAL_MODELS = new ThreadLocal<>();

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadAllBlockStates()V"))
    private void bootoptim$blockstates(BlockStateModelLoader loader) {
        var stamp = VarianceProbe.start("bakery_blockstate_registration");
        loader.loadAllBlockStates();
        VarianceProbe.finish("bakery_blockstate_registration", stamp);
        BOOTOPTIM$AFTER_BLOCKSTATES.set(VarianceProbe.start("bakery_post_blockstates_to_items"));
    }

    // The CIT item-model injection is attached before this first stock popPush("items").
    // This interval includes that callback plus getModelGroups; it does not label all of it CIT.
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", ordinal = 0, shift = At.Shift.AFTER))
    private void bootoptim$itemsBegin(CallbackInfo ci) {
        VarianceProbe.finish("bakery_post_blockstates_to_items", BOOTOPTIM$AFTER_BLOCKSTATES.get());
        BOOTOPTIM$AFTER_BLOCKSTATES.remove();
        BOOTOPTIM$VANILLA_ITEMS.set(VarianceProbe.start("bakery_vanilla_item_loop"));
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", ordinal = 1))
    private void bootoptim$itemsEnd(CallbackInfo ci) {
        VarianceProbe.finish("bakery_vanilla_item_loop", BOOTOPTIM$VANILLA_ITEMS.get());
        BOOTOPTIM$VANILLA_ITEMS.remove();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void bootoptim$parents(Collection<UnbakedModel> models, Consumer<UnbakedModel> resolver) {
        VarianceProbe.finish("bakery_additional_models_loop", BOOTOPTIM$ADDITIONAL_MODELS.get());
        BOOTOPTIM$ADDITIONAL_MODELS.remove();
        var stamp = VarianceProbe.start("bakery_parent_resolution");
        models.forEach(resolver);
        VarianceProbe.finish("bakery_parent_resolution", stamp);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onRegisterAdditionalModels(Ljava/util/Set;)V", remap = false))
    private void bootoptim$additional(Set<ModelResourceLocation> models) {
        var stamp = VarianceProbe.start("bakery_additional_model_event");
        ClientHooks.onRegisterAdditionalModels(models);
        VarianceProbe.finish("bakery_additional_model_event", stamp);
        BOOTOPTIM$ADDITIONAL_MODELS.set(VarianceProbe.start("bakery_additional_models_loop"));
    }
}
