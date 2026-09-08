package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.MoreCullingParallelReload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MoreCulling 1.0.x adds these synthetic methods to Minecraft. Keeping the
 * method names and invocation targets as strings means this mixin is inert when
 * MoreCulling is absent; no hard dependency is introduced.
 */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class MoreCullingParallelReloadMixin {
    @Redirect(
            method = "lambda$moreculling$onBlockRenderManagerInitialized$1",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Iterable;forEach(Ljava/util/function/Consumer;)V"
            ),
            remap = false
    )
    private static void bootoptim$parallelShapeCache(Iterable<?> states, Consumer<Object> action) {
        MoreCullingParallelReload.states(states, action);
    }

    @Redirect(
            method = "lambda$moreculling$onBlockRenderManagerInitialized$3",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"
            ),
            remap = false
    )
    private static void bootoptim$parallelOpacityCache(Map<?, ?> models, BiConsumer<Object, Object> action) {
        MoreCullingParallelReload.models(models, action);
    }
}
