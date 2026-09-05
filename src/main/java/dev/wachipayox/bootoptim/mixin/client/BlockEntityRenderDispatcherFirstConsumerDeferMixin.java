package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Experimental first-consumer defer for the initial block-entity-renderer reconstruction. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherFirstConsumerDeferMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentRendererFirstConsumerDefer", "false"));

    @Unique
    private boolean bootoptim$initialReloadSeen;

    @Unique
    private volatile ResourceManager bootoptim$pendingResourceManager;

    @Unique
    private volatile boolean bootoptim$forcing;

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), cancellable = true, require = 1)
    private void bootoptim$deferInitialRendererReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || this.bootoptim$forcing) {
            return;
        }

        if (!this.bootoptim$initialReloadSeen) {
            this.bootoptim$initialReloadSeen = true;
            this.bootoptim$pendingResourceManager = resourceManager;
            BOOTOPTIM$LOGGER.info(
                    "BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=block_entity status=deferred thread={}",
                    Thread.currentThread().getName());
            ci.cancel();
            return;
        }

        if (this.bootoptim$pendingResourceManager != null) {
            this.bootoptim$pendingResourceManager = null;
            BOOTOPTIM$LOGGER.info(
                    "BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=block_entity status=superseded_by_reload thread={}",
                    Thread.currentThread().getName());
        }
    }

    @Inject(method = "getRenderer", at = @At("HEAD"), require = 1)
    private void bootoptim$forceBeforeRendererLookup(
            BlockEntity blockEntity, CallbackInfoReturnable<BlockEntityRenderer<?>> cir) {
        this.bootoptim$forcePendingReload("getRenderer");
    }

    @Unique
    private void bootoptim$forcePendingReload(String consumer) {
        if (!BOOTOPTIM$ENABLED || this.bootoptim$pendingResourceManager == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.executeBlocking(() -> this.bootoptim$forcePendingReload(consumer));
            return;
        }

        ResourceManager resourceManager = this.bootoptim$pendingResourceManager;
        if (resourceManager == null || this.bootoptim$forcing) {
            return;
        }

        this.bootoptim$forcing = true;
        long startNanos = System.nanoTime();
        try {
            ((BlockEntityRenderDispatcher) (Object) this).onResourceManagerReload(resourceManager);
            this.bootoptim$pendingResourceManager = null;
            BOOTOPTIM$LOGGER.info(
                    "BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=block_entity status=forced consumer={} force_ms={} thread={}",
                    consumer,
                    String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000.0D),
                    Thread.currentThread().getName());
        } finally {
            this.bootoptim$forcing = false;
        }
    }
}
