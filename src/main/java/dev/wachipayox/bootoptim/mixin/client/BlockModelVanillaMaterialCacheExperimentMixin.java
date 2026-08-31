package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.GeneratedMaterialCacheBridge;
import dev.wachipayox.bootoptim.profiling.client.ShortScopeMaterialCacheExperiment;
import java.util.Arrays;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic cache attached only to temporary BlockModels created by ItemModelGenerator. */
@Mixin(BlockModel.class)
abstract class BlockModelVanillaMaterialCacheExperimentMixin implements GeneratedMaterialCacheBridge {
    @Unique private boolean bootoptim$generatedMaterialCache;
    @Unique private String[] bootoptim$materialNames;
    @Unique private Material[] bootoptim$materials;
    @Unique private int bootoptim$materialCount;

    @Override
    public void bootoptim$enableGeneratedMaterialCache() {
        if (bootoptim$generatedMaterialCache) return;
        bootoptim$generatedMaterialCache = true;
        bootoptim$materialNames = new String[8];
        bootoptim$materials = new Material[8];
        ShortScopeMaterialCacheExperiment.recordGeneratedModel();
    }

    @Inject(
            method = "getMaterial(Ljava/lang/String;)Lnet/minecraft/client/resources/model/Material;",
            at = @At("HEAD"),
            cancellable = true)
    private void bootoptim$lookupGeneratedMaterial(String name, CallbackInfoReturnable<Material> cir) {
        if (!bootoptim$generatedMaterialCache) return;
        for (int i = 0; i < bootoptim$materialCount; i++) {
            if (bootoptim$materialNames[i].equals(name)) {
                ShortScopeMaterialCacheExperiment.recordGeneratedMaterialCall(true);
                cir.setReturnValue(bootoptim$materials[i]);
                return;
            }
        }
        ShortScopeMaterialCacheExperiment.recordGeneratedMaterialCall(false);
    }

    @Inject(
            method = "getMaterial(Ljava/lang/String;)Lnet/minecraft/client/resources/model/Material;",
            at = @At("RETURN"))
    private void bootoptim$storeGeneratedMaterial(String name, CallbackInfoReturnable<Material> cir) {
        if (!bootoptim$generatedMaterialCache) return;
        for (int i = 0; i < bootoptim$materialCount; i++) {
            if (bootoptim$materialNames[i].equals(name)) return;
        }
        if (bootoptim$materialCount == bootoptim$materialNames.length) {
            bootoptim$materialNames = Arrays.copyOf(bootoptim$materialNames, bootoptim$materialNames.length * 2);
            bootoptim$materials = Arrays.copyOf(bootoptim$materials, bootoptim$materials.length * 2);
        }
        bootoptim$materialNames[bootoptim$materialCount] = name;
        bootoptim$materials[bootoptim$materialCount] = cir.getReturnValue();
        bootoptim$materialCount++;
        ShortScopeMaterialCacheExperiment.recordGeneratedEntries(bootoptim$materialCount);
    }
}
