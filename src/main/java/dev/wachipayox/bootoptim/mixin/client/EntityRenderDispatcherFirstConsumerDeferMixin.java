package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Experimental first-consumer defer for the initial entity-renderer reconstruction.
 *
 * <p>The first startup reload is retained as a pending authoritative ResourceManager instead of
 * reconstructing every renderer before the title screen. The original onResourceManagerReload
 * method is invoked exactly once on Minecraft's client thread before the first renderer-map
 * consumer. Subsequent reloads stay stock and supersede a still-pending startup reload.</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherFirstConsumerDeferMixin {
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
                    "BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=entity status=deferred thread={}",
                    Thread.currentThread().getName());
            ci.cancel();
            return;
        }

        if (this.bootoptim$pendingResourceManager != null) {
            this.bootoptim$pendingResourceManager = null;
            BOOTOPTIM$LOGGER.info(
                    "BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=entity status=superseded_by_reload thread={}",
                    Thread.currentThread().getName());
        }
    }

    @Inject(method = "getRenderer", at = @At("HEAD"), require = 1)
    private void bootoptim$forceBeforeRendererLookup(
            Entity entity, CallbackInfoReturnable<EntityRenderer<?>> cir) {
        this.bootoptim$forcePendingReload("getRenderer");
    }

    @Inject(method = "getSkinMap", at = @At("HEAD"), require = 1)
    private void bootoptim$forceBeforeSkinMapLookup(
            CallbackInfoReturnable<Map<PlayerSkin.Model, EntityRenderer<? extends Player>>> cir) {
        this.bootoptim$forcePendingReload("getSkinMap");
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
            ((EntityRenderDispatcher) (Object) this).onResourceManagerReload(resourceManager);
            this.bootoptim$pendingResourceManager = null;
            BOOTOPTIM$LOGGER.info(
                    "BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=entity status=forced consumer={} force_ms={} thread={}",
                    consumer,
                    String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000.0D),
                    Thread.currentThread().getName());
        } finally {
            this.bootoptim$forcing = false;
        }
    }
}
