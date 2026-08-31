package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.client.resources.model.Material;

/** Internal bridge for the diagnostic BlockGeometryBakingContext material cache. */
public interface BlockGeometryMaterialCacheBridge {
    Material bootoptim$getCachedMaterial(String name);
}
