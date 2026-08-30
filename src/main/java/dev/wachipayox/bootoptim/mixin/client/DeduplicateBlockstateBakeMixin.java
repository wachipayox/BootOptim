package dev.wachipayox.bootoptim.mixin.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
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

/**
 * Avoids repeated eager top-level bakes when multiple blockstate locations point at the exact
 * same vanilla MultiVariant or MultiPart object.
 *
 * <p>The cache is local to one ModelBakery#bakeModels invocation, uses object identity rather than
 * equals(), and only accepts the two exact vanilla unbaked model classes. Custom subclasses and
 * every other model type always execute the original bake path. Iteration remains sequential and
 * in the original order.</p>
 */
@Mixin(ModelBakery.class)
abstract class DeduplicateBlockstateBakeMixin {
    private static final Logger BOOTOPTIM$LOGGER = LoggerFactory.getLogger("BootOptim/ModelBakeDedup");
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.deduplicateBlockstateBake", "true"));

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

        long startedNanos = System.nanoTime();
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

        double elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000.0D;
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_MODEL_DEDUP total_entries={} safe_entries={} unique_safe_bakes={} reused_bakes={} reuse_percent={} cache_entries={} elapsed_ms={}",
                models.size(),
                safeEntries,
                uniqueSafeBakes,
                reusedBakes,
                safeEntries == 0
                        ? "0.00"
                        : String.format(java.util.Locale.ROOT, "%.2f", reusedBakes * 100.0D / safeEntries),
                bakedByIdentity.size(),
                String.format(java.util.Locale.ROOT, "%.3f", elapsedMs));
    }

    private static boolean bootoptim$isSafeVanillaBlockstateModel(UnbakedModel model) {
        Class<?> type = model.getClass();
        return type == MultiVariant.class || type == MultiPart.class;
    }
}
