package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import dev.wachipayox.bootoptim.profiling.StartupReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional per-model memoization for the read-only texture lookup used by the
 * vanilla block-model baker. It is deliberately disabled by default until an
 * exact-pack A/B proves a critical-path win.
 */
@Mixin(BlockModel.class)
abstract class BlockModelMaterialCacheMixin {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.blockModelMaterialCache", "false"));
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    @Shadow
    private BlockModel parent;

    @Shadow
    private ResourceLocation parentLocation;

    @Unique
    private Map<String, Material> bootoptim$materialCache;

    @Unique
    private String bootoptim$singleMaterialKey;

    @Unique
    private Material bootoptim$singleMaterialValue;

    @Inject(method = "getMaterial", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$lookupMaterial(String texture, CallbackInfoReturnable<Material> cir) {
        if (!ENABLED || texture == null || !bootoptim$parentsResolved()) {
            return;
        }
        String key = bootoptim$normalize(texture);
        if (key.equals(bootoptim$singleMaterialKey)) {
            bootoptim$reportActive();
            cir.setReturnValue(bootoptim$singleMaterialValue);
            return;
        }
        Map<String, Material> cache = bootoptim$materialCache;
        if (cache != null && cache.containsKey(key)) {
            bootoptim$reportActive();
            cir.setReturnValue(cache.get(key));
        }
    }

    @Inject(method = "getMaterial", at = @At("RETURN"), require = 0)
    private void bootoptim$rememberMaterial(String texture, CallbackInfoReturnable<Material> cir) {
        if (!ENABLED || texture == null || !bootoptim$parentsResolved()) {
            return;
        }
        String key = bootoptim$normalize(texture);
        if (key.equals(bootoptim$singleMaterialKey)) {
            return;
        }
        if (bootoptim$singleMaterialKey == null) {
            bootoptim$singleMaterialKey = key;
            bootoptim$singleMaterialValue = cir.getReturnValue();
            bootoptim$reportActive();
            return;
        }
        if (bootoptim$materialCache == null) {
            bootoptim$materialCache = new HashMap<>();
            bootoptim$materialCache.put(bootoptim$singleMaterialKey, bootoptim$singleMaterialValue);
        }
        bootoptim$materialCache.putIfAbsent(key, cir.getReturnValue());
    }

    @Unique
    private boolean bootoptim$parentsResolved() {
        // During ModelBakery construction a parent can still be unresolved:
        // parentLocation is non-null while parent is null. Do not memoize that
        // transient result; after resolution either there is no parent location
        // or the parent reference is populated.
        return this.parentLocation == null || this.parent != null;
    }

    @Unique
    private static void bootoptim$reportActive() {
        if (REPORTED.compareAndSet(false, true)) {
            StartupReport.optimization("block_model_material_cache", true, "per_model_resolved_material");
        }
    }

    @Unique
    private static String bootoptim$normalize(String texture) {
        return texture.startsWith("#") ? texture.substring(1) : texture;
    }
}
