package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.core.Direction;

/** Access surface for the optional per-Transformation direction cache. */
interface TransformationDirectionCacheAccess {
    Direction bootoptim$getCachedDirection(Direction direction);
}
