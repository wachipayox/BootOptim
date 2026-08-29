package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Experimental backport of the parallel model-baking direction used by newer Minecraft versions.
 *
 * <p>This deliberately preserves 1.21.1's eager model lifecycle: every top-level model is still baked
 * before NeoForge's post-bake hooks run. Only the independent top-level bake loop is parallelized.
 * The branch is benchmark-only until it has been exercised against the real pack.</p>
 */
@Mixin(ModelBakery.class)
abstract class ParallelModelBakeExperimentMixin {
    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.getBoolean("boot_optim.parallelModelBake");

    @Unique
    private static final int BOOTOPTIM$MIN_MODELS = 64;

    // The real key type is ModelBakery.BakedCacheKey, which is package-private. Generic erasure is Map.
    @Shadow
    @Final
    @Mutable
    private Map<Object, BakedModel> bakedCache;

    @Shadow
    @Final
    @Mutable
    private Map<ModelResourceLocation, BakedModel> bakedTopLevelModels;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$makeBakeOutputsThreadSafe(CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED) {
            return;
        }

        // Nested dependency baking shares this cache. Keep support for null values because the
        // vanilla HashMap accepts them and custom model implementations may legally return null.
        this.bakedCache = Collections.synchronizedMap(new HashMap<>(this.bakedCache));
        this.bakedTopLevelModels = new ConcurrentHashMap<>(this.bakedTopLevelModels);
    }

    @Redirect(
            method = "bakeModels",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void bootoptim$parallelTopLevelBake(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> bakeAction) {
        if (!BOOTOPTIM$ENABLED || models.size() < BOOTOPTIM$MIN_MODELS) {
            models.forEach(bakeAction);
            return;
        }

        // ModelBakery construction has already discovered dependencies and resolved parents. The
        // top-level bake loop is therefore read-mostly; only the baked caches above are shared writes.
        models.entrySet().parallelStream().forEach(entry -> bakeAction.accept(entry.getKey(), entry.getValue()));
    }
}
