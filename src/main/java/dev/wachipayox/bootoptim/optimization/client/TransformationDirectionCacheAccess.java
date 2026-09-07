package dev.wachipayox.bootoptim.optimization.client;

import net.minecraft.core.Direction;

/** Access surface for the optional per-Transformation direction cache. */
public interface TransformationDirectionCacheAccess {
    Direction bootoptim$getCachedDirection(Direction direction);
}
