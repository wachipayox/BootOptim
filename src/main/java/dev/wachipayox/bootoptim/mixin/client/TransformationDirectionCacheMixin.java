package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import dev.wachipayox.bootoptim.optimization.client.TransformationDirectionCacheAccess;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Stores cull-direction results on the Transformation that owns the matrix.
 * Both the array and each direction entry are lazy, so a transformation used
 * by a model with one cull face pays for one mapping rather than all six. The
 * cache dies with that Transformation object; it is not a global
 * model/resource-reload cache.
 */
@Mixin(Transformation.class)
abstract class TransformationDirectionCacheMixin implements TransformationDirectionCacheAccess {
    @Unique
    private volatile Direction[] bootoptim$directionCache;

    @Override
    @Unique
    public Direction bootoptim$getCachedDirection(Direction direction) {
        int ordinal = direction.ordinal();
        Direction[] cached = bootoptim$directionCache;
        Direction resolved = cached == null ? null : cached[ordinal];
        if (resolved == null) {
            synchronized (this) {
                cached = bootoptim$directionCache;
                if (cached == null) {
                    cached = new Direction[Direction.values().length];
                    bootoptim$directionCache = cached;
                }
                resolved = cached[ordinal];
                if (resolved == null) {
                    Transformation self = (Transformation) (Object) this;
                    resolved = Direction.rotate(self.getMatrix(), direction);
                    cached[ordinal] = resolved;
                }
            }
        }
        return resolved;
    }
}
