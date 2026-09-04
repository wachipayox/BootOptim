package dev.wachipayox.bootoptim.optimization.client;

/**
 * Internal bridge implemented by the renderer-dispatcher mixins.
 *
 * <p>The coordinator uses this only to force the already-retained authoritative reload. It does
 * not expose or replace renderer maps.</p>
 */
public interface DeferredRendererReloadAccess {
    boolean bootoptim$hasPendingRendererReload();

    void bootoptim$forcePendingRendererReload(String consumer);
}
