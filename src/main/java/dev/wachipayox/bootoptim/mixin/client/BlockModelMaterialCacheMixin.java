package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional per-model memoization for the read-only texture lookup used by the
 * vanilla block-model baker. It is deliberately disabled by default until an
 * exact-pack A/B proves a critical-path win.
 */
@Mixin(BlockModel.class)
abstract class BlockModelMaterialCacheMixin {
    private static final String PROPERTY = "boot_optim.blockModelMaterialCache";

    @Shadow
    private BlockModel parent;

    @Shadow
    private ResourceLocation parentLocation;

    @Unique
    private Map<String, Material> bootoptim$materialCache;

    @Inject(method = "getMaterial", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$lookupMaterial(String texture, CallbackInfoReturnable<Material> cir) {
        if (!bootoptim$enabled() || texture == null || !bootoptim$parentsResolved()) {
            return;
        }
        String key = bootoptim$normalize(texture);
        Map<String, Material> cache = bootoptim$materialCache;
        if (cache != null && cache.containsKey(key)) {
            cir.setReturnValue(cache.get(key));
        }
    }

    @Inject(method = "getMaterial", at = @At("RETURN"), require = 0)
    private void bootoptim$rememberMaterial(String texture, CallbackInfoReturnable<Material> cir) {
        if (!bootoptim$enabled() || texture == null || !bootoptim$parentsResolved()) {
            return;
        }
        if (bootoptim$materialCache == null) {
            bootoptim$materialCache = new HashMap<>();
        }
        bootoptim$materialCache.putIfAbsent(bootoptim$normalize(texture), cir.getReturnValue());
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
    private static boolean bootoptim$enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    @Unique
    private static String bootoptim$normalize(String texture) {
        return texture.startsWith("#") ? texture.substring(1) : texture;
    }
}
