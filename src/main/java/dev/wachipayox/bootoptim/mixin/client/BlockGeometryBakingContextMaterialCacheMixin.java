package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.BlockGeometryMaterialCacheBridge;
import dev.wachipayox.bootoptim.profiling.client.ShortScopeMaterialCacheExperiment;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

/**
 * Diagnostic cache on NeoForge's stock BlockGeometryBakingContext.
 *
 * <p>The context is permanently bound to one BlockModel and its getMaterial implementation is exactly
 * owner.getMaterial(name). ElementsModel can therefore reuse resolved materials across repeated bakes of the same
 * BlockModel without caching arbitrary modded IGeometryBakingContext implementations.</p>
 */
@Mixin(BlockGeometryBakingContext.class)
abstract class BlockGeometryBakingContextMaterialCacheMixin implements BlockGeometryMaterialCacheBridge {
    @Shadow @Final public BlockModel owner;

    @Unique private String[] bootoptim$materialNames;
    @Unique private Material[] bootoptim$materials;
    @Unique private int bootoptim$materialCount;
    @Unique private String bootoptim$lastMaterialName;
    @Unique private Material bootoptim$lastMaterial;

    @Override
    public Material bootoptim$getCachedMaterial(String name) {
        String lastName = bootoptim$lastMaterialName;
        if (lastName != null && lastName.equals(name)) {
            ShortScopeMaterialCacheExperiment.recordHit(bootoptim$materialCount);
            return bootoptim$lastMaterial;
        }

        String[] names = bootoptim$materialNames;
        if (names != null) {
            for (int i = 0; i < bootoptim$materialCount; i++) {
                if (names[i].equals(name)) {
                    Material cached = bootoptim$materials[i];
                    bootoptim$lastMaterialName = names[i];
                    bootoptim$lastMaterial = cached;
                    ShortScopeMaterialCacheExperiment.recordHit(bootoptim$materialCount);
                    return cached;
                }
            }
        }

        Material resolved = owner.getMaterial(name);
        if (names == null) {
            names = new String[4];
            bootoptim$materialNames = names;
            bootoptim$materials = new Material[4];
            ShortScopeMaterialCacheExperiment.recordContext();
        } else if (bootoptim$materialCount == names.length) {
            int newLength = names.length * 2;
            names = Arrays.copyOf(names, newLength);
            bootoptim$materialNames = names;
            bootoptim$materials = Arrays.copyOf(bootoptim$materials, newLength);
        }

        names[bootoptim$materialCount] = name;
        bootoptim$materials[bootoptim$materialCount] = resolved;
        bootoptim$materialCount++;
        bootoptim$lastMaterialName = name;
        bootoptim$lastMaterial = resolved;
        ShortScopeMaterialCacheExperiment.recordMiss(bootoptim$materialCount);
        return resolved;
    }
}
