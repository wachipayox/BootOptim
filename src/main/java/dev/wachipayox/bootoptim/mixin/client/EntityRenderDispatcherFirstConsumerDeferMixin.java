package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.optimization.client.DeferredRendererReloadAccess;
import dev.wachipayox.bootoptim.optimization.client.RendererReloadCoordinator;
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

/** Experimental first-consumer defer for the initial entity-renderer reconstruction. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherFirstConsumerDeferMixin implements DeferredRendererReloadAccess {
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
        if (BOOTOPTIM$ENABLED) {
            RendererReloadCoordinator.forcePending("entity:getRenderer");
        }
    }

    @Inject(method = "getSkinMap", at = @At("HEAD"), require = 1)
    private void bootoptim$forceBeforeSkinMapLookup(
            CallbackInfoReturnable<Map<PlayerSkin.Model, EntityRenderer<? extends Player>>> cir) {
        if (BOOTOPTIM$ENABLED) {
            RendererReloadCoordinator.forcePending("entity:getSkinMap");
        }
    }

    @Override
    public boolean bootoptim$hasPendingRendererReload() {
        return BOOTOPTIM$ENABLED && this.bootoptim$pendingResourceManager != null;
    }

    @Override
    public void bootoptim$forcePendingRendererReload(String consumer) {
        if (!BOOTOPTIM$ENABLED || this.bootoptim$pendingResourceManager == null || this.bootoptim$forcing) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.executeBlocking(() -> this.bootoptim$forcePendingRendererReload(consumer));
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
