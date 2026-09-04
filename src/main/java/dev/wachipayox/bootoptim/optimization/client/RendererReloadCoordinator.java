package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/** Coordinates the two deferred renderer reload listeners in their original vanilla order. */
public final class RendererReloadCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Accessed only on Minecraft's client thread after off-thread callers are marshalled there. */
    private static boolean forcingAll;

    private RendererReloadCoordinator() {}

    /**
     * Forces all pending renderer reload work before returning.
     *
     * <p>Vanilla 1.21.1 registers the block-entity dispatcher before the entity dispatcher. Keep
     * that ordering even if an entity lookup was the first consumer. Off-thread callers block on
     * Minecraft's client executor so renderer/provider construction still runs on the client/render
     * thread.</p>
     */
    public static void forcePending(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.executeBlocking(() -> forcePending(reason));
            return;
        }

        // A provider constructed by the stock reload may perform renderer lookups. In the eager
        // original those lookups do not recursively launch another resource reload, so neither do we.
        if (forcingAll) {
            return;
        }

        DeferredRendererReloadAccess blockEntities =
                (DeferredRendererReloadAccess) (Object) minecraft.getBlockEntityRenderDispatcher();
        DeferredRendererReloadAccess entities =
                (DeferredRendererReloadAccess) (Object) minecraft.getEntityRenderDispatcher();

        boolean blockPending = blockEntities.bootoptim$hasPendingRendererReload();
        boolean entityPending = entities.bootoptim$hasPendingRendererReload();
        if (!blockPending && !entityPending) {
            return;
        }

        forcingAll = true;
        long startNanos = System.nanoTime();
        try {
            LOGGER.info(
                    "BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=forcing reason={} block_pending={} entity_pending={} thread={}",
                    reason,
                    blockPending,
                    entityPending,
                    Thread.currentThread().getName());

            // Preserve Minecraft's initial reload listener registration order.
            blockEntities.bootoptim$forcePendingRendererReload("coordinator:" + reason);
            entities.bootoptim$forcePendingRendererReload("coordinator:" + reason);

            LOGGER.info(
                    "BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=complete reason={} total_ms={} block_pending={} entity_pending={} thread={}",
                    reason,
                    String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000.0D),
                    blockEntities.bootoptim$hasPendingRendererReload(),
                    entities.bootoptim$hasPendingRendererReload(),
                    Thread.currentThread().getName());
        } finally {
            forcingAll = false;
        }
    }
}
