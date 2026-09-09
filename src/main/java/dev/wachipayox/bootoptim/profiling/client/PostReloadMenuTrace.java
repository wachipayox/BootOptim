package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.RegularBootTraceBridge;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

/**
 * Coarse diagnostic boundaries from initial reload completion to the first actually presented title frame.
 *
 * <p>Screen lifecycle events are observed on the client/render thread. The presentation token is thread-local:
 * only the same thread that observed a completed {@code TitleScreen} render can consume it after
 * {@code Window.updateDisplay()} returns. No rendering, GLFW call, future or executor is wrapped or rescheduled.</p>
 */
public final class PostReloadMenuTrace {
    private static final String MODE_PROPERTY = "boot_optim.bootTrace.mode";
    private static final String ENDPOINT_PROPERTY = "boot_optim.bootTrace.endpoint";
    private static final String PRESENTED_ENDPOINT = "main_menu_presented";

    private static final AtomicBoolean TITLE_OPEN_RECORDED = new AtomicBoolean();
    private static final AtomicBoolean TITLE_INIT_RECORDED = new AtomicBoolean();
    private static final AtomicBoolean TITLE_RENDER_RECORDED = new AtomicBoolean();
    private static final AtomicBoolean TITLE_PRESENT_RECORDED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> TITLE_RENDERED_ON_THREAD = new ThreadLocal<>();

    private PostReloadMenuTrace() {
    }

    public static boolean awaitPresentedEndpoint() {
        if (!traceEnabled()) {
            return false;
        }
        String endpoint = System.getProperty(ENDPOINT_PROPERTY, "");
        return PRESENTED_ENDPOINT.equalsIgnoreCase(endpoint == null ? "" : endpoint.trim());
    }

    public static void markTitleOpening() {
        recordOnce(TITLE_OPEN_RECORDED, "title_open", "ScreenEvent.Opening(TitleScreen)");
    }

    public static void markTitleInitPost() {
        recordOnce(TITLE_INIT_RECORDED, "title_init_post", "ScreenEvent.Init.Post(TitleScreen)");
    }

    public static void markTitleRenderReturn() {
        if (!awaitPresentedEndpoint()) {
            return;
        }
        recordOnce(TITLE_RENDER_RECORDED, "title_render_return", "RenderFrameEvent.Post(TitleScreen active)");
        TITLE_RENDERED_ON_THREAD.set(Boolean.TRUE);
    }

    /** Called only from the injection immediately after stock Window.updateDisplay(). */
    public static void onWindowPresentReturn() {
        if (!awaitPresentedEndpoint() || !Boolean.TRUE.equals(TITLE_RENDERED_ON_THREAD.get())) {
            return;
        }
        TITLE_RENDERED_ON_THREAD.remove();
        if (!TITLE_PRESENT_RECORDED.compareAndSet(false, true)) {
            return;
        }

        RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                PRESENTED_ENDPOINT, -1L, "boot_optim", null, 1L,
                "first_updateDisplay_return_after_TitleScreen_RenderFrameEvent_Post");

        // Exact-pack benchmark mode normally exits at ScreenEvent.Opening. For this diagnostic endpoint only,
        // ClientStartupHooks defers that stop until this first-present boundary has returned on the render thread.
        if (StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }

    private static void recordOnce(AtomicBoolean guard, String phase, String detail) {
        if (!awaitPresentedEndpoint() || !guard.compareAndSet(false, true)) {
            return;
        }
        RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                phase, -1L, "boot_optim", null, 1L, detail);
    }

    private static boolean traceEnabled() {
        String mode = System.getProperty(MODE_PROPERTY, "off");
        return mode != null && !mode.isBlank() && !"off".equalsIgnoreCase(mode.trim());
    }
}
