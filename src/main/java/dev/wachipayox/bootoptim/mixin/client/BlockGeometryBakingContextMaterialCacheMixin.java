package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.datafixers.util.Either;
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
import java.util.Optional;

/**
 * Diagnostic selective cache on NeoForge's stock BlockGeometryBakingContext.
 *
 * <p>A previous context-scoped experiment cached every material lookup. The exact pack showed that this removed many
 * repeated owner.getMaterial calls, but the lookup machinery still cost more than the work it avoided. Most texture
 * lookups are expected to be the cheap case: a material stored directly on the owner BlockModel. This variant returns
 * that direct local material without touching the cache and only memoizes names that actually require a reference or
 * parent-chain resolution.</p>
 *
 * <p>The context is permanently bound to one BlockModel. Arbitrary modded IGeometryBakingContext implementations are
 * still untouched by the ElementsModel redirect.</p>
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
        // Fastest repeated expensive-path case. Direct-local entries are never inserted here.
        String lastName = bootoptim$lastMaterialName;
        if (lastName != null && lastName.equals(name)) {
            ShortScopeMaterialCacheExperiment.recordComplexHit(bootoptim$materialCount);
            return bootoptim$lastMaterial;
        }

        // Mirror the first iteration of BlockModel#getMaterial. A direct material on the owner is already cheap;
        // bypassing the cache avoids paying a linear lookup for the overwhelmingly common case while also avoiding
        // BlockModel#getMaterial's temporary reference-chain list allocation.
        if (!name.isEmpty()) {
            String localName = name.charAt(0) == '#' ? name.substring(1) : name;
            Either<Material, String> localEntry = owner.textureMap.get(localName);
            if (localEntry != null) {
                Optional<Material> direct = localEntry.left();
                if (direct.isPresent()) {
                    ShortScopeMaterialCacheExperiment.recordDirectLocal();
                    return direct.get();
                }
            }
        }

        // Only reference/parent/missing-texture paths reach the cache.
        String[] names = bootoptim$materialNames;
        if (names != null) {
            for (int i = 0; i < bootoptim$materialCount; i++) {
                if (names[i].equals(name)) {
                    Material cached = bootoptim$materials[i];
                    bootoptim$lastMaterialName = names[i];
                    bootoptim$lastMaterial = cached;
                    ShortScopeMaterialCacheExperiment.recordComplexHit(bootoptim$materialCount);
                    return cached;
                }
            }
        }

        // Keep stock behavior for the actual non-trivial resolution, including malformed names, missing textures and
        // reference-loop warnings. We only memoize its final Material for subsequent requests on this same context.
        Material resolved = owner.getMaterial(name);
        if (names == null) {
            names = new String[4];
            bootoptim$materialNames = names;
            bootoptim$materials = new Material[4];
            ShortScopeMaterialCacheExperiment.recordCachedContext();
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
        ShortScopeMaterialCacheExperiment.recordComplexMiss(bootoptim$materialCount);
        return resolved;
    }
}
