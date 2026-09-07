package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Stores the six immutable cull-direction results on the Transformation that
 * owns the matrix. The cache is lazy and dies with that Transformation object;
 * it is not a global model/resource-reload cache.
 */
@Mixin(Transformation.class)
abstract class TransformationDirectionCacheMixin implements TransformationDirectionCacheAccess {
    @Unique
    private volatile Direction[] bootoptim$directionCache;

    @Override
    @Unique
    public Direction bootoptim$getCachedDirection(Direction direction) {
        Direction[] cached = bootoptim$directionCache;
        if (cached == null) {
            synchronized (this) {
                cached = bootoptim$directionCache;
                if (cached == null) {
                    Transformation self = (Transformation) (Object) this;
                    Direction[] computed = new Direction[Direction.values().length];
                    for (Direction value : Direction.values()) {
                        computed[value.ordinal()] = Direction.rotate(self.getMatrix(), value);
                    }
                    bootoptim$directionCache = cached = computed;
                }
            }
        }
        return cached[direction.ordinal()];
    }
}
