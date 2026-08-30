package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Benchmark experiment that removes redundant eager top-level bakes for vanilla blockstate models.
 *
 * <p>BlockStateModelLoader registers one top-level entry per BlockState, while multiple states can
 * deliberately reference the exact same MultiVariant or MultiPart object. Vanilla then invokes
 * bakeUncached once for every top-level entry. For the two exact vanilla classes below, baking is
 * independent of the top-level ModelResourceLocation; the bound texture getter only changes which
 * model id receives a missing-texture diagnostic. Reusing the first successful baked result therefore
 * preserves model geometry while avoiding repeated work.</p>
 *
 * <p>Custom subclasses and every other UnbakedModel type always take the original path. Execution is
 * still sequential and the original bake action is called unchanged for each unique safe identity.</p>
 */
@Mixin(ModelBakery.class)
abstract class DeduplicateBlockstateBakeExperimentMixin {
    private static final Logger BOOTOPTIM$LOGGER = LoggerFactory.getLogger("BootOptim/ModelBakeDedup");
    private static final boolean BOOTOPTIM$ENABLED =
            Boolean.parseBoolean(System.getProperty("boot_optim.deduplicateBlockstateBake", "true"));

    @Shadow
    @Final
    private Map<ModelResourceLocation, BakedModel> bakedTopLevelModels;

    @Redirect(
            method = "bakeModels",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void bootoptim$deduplicateSafeTopLevelBakes(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> bakeAction) {
        if (!BOOTOPTIM$ENABLED) {
            models.forEach(bakeAction);
            return;
        }

        IdentityHashMap<UnbakedModel, BakedModel> bakedByIdentity = new IdentityHashMap<>();
        int safeEntries = 0;
        int uniqueSafeBakes = 0;
        int reusedBakes = 0;

        for (Map.Entry<ModelResourceLocation, UnbakedModel> entry : models.entrySet()) {
            ModelResourceLocation location = entry.getKey();
            UnbakedModel unbakedModel = entry.getValue();
            boolean safe = bootoptim$isSafeVanillaBlockstateModel(unbakedModel);

            if (safe) {
                safeEntries++;
                BakedModel cached = bakedByIdentity.get(unbakedModel);
                if (cached != null) {
                    this.bakedTopLevelModels.put(location, cached);
                    reusedBakes++;
                    continue;
                }
            }

            bakeAction.accept(location, unbakedModel);

            if (safe) {
                BakedModel baked = this.bakedTopLevelModels.get(location);
                if (baked != null) {
                    bakedByIdentity.put(unbakedModel, baked);
                    uniqueSafeBakes++;
                }
            }
        }

        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_MODEL_DEDUP total_entries={} safe_entries={} unique_safe_bakes={} reused_bakes={} reuse_percent={}",
                models.size(),
                safeEntries,
                uniqueSafeBakes,
                reusedBakes,
                safeEntries == 0 ? "0.00" : String.format(java.util.Locale.ROOT, "%.2f", reusedBakes * 100.0 / safeEntries));
    }

    private static boolean bootoptim$isSafeVanillaBlockstateModel(UnbakedModel model) {
        Class<?> type = model.getClass();
        return type == MultiVariant.class || type == MultiPart.class;
    }
}
