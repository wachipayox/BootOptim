package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.RegularBootTraceBridge;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Coarse diagnostic boundaries from initial reload completion to the first actually presented startup UI.
 *
 * <p>The exact pack can synchronously replace its first {@code TitleScreen} during screen initialization. This probe
 * therefore records the real active screen identity rather than manufacturing a title frame. Screen/render callbacks
 * are observed on the client/render thread, and the presentation token is thread-local: only the same thread that
 * observed a completed frame can consume it immediately after stock {@code Window.updateDisplay()} returns. No
 * rendering, GLFW call, future or executor is wrapped or rescheduled.</p>
 */
public final class PostReloadMenuTrace {
    private static final String MODE_PROPERTY = "boot_optim.bootTrace.mode";
    private static final String ENDPOINT_PROPERTY = "boot_optim.bootTrace.endpoint";
    private static final String PRESENTED_ENDPOINT = "startup_ui_presented";

    private static final AtomicBoolean TITLE_OPEN_RECORDED = new AtomicBoolean();
    private static final AtomicBoolean TITLE_INIT_RECORDED = new AtomicBoolean();
    private static final AtomicBoolean UI_RENDER_RECORDED = new AtomicBoolean();
    private static final AtomicBoolean UI_PRESENT_RECORDED = new AtomicBoolean();
    private static final AtomicInteger REPLACEMENT_OPENINGS = new AtomicInteger();
    private static final ThreadLocal<String> RENDERED_SCREEN_ON_THREAD = new ThreadLocal<>();

    private PostReloadMenuTrace() {
    }

    public static boolean awaitPresentedEndpoint() {
        if (!traceEnabled()) {
            return false;
        }
        String endpoint = System.getProperty(ENDPOINT_PROPERTY, "");
        return PRESENTED_ENDPOINT.equalsIgnoreCase(endpoint == null ? "" : endpoint.trim());
    }

    public static void markScreenOpening(Screen screen) {
        if (!awaitPresentedEndpoint() || screen == null) {
            return;
        }
        if (screen instanceof TitleScreen) {
            recordOnce(TITLE_OPEN_RECORDED, "title_open", "screen=" + screen.getClass().getName());
            return;
        }
        if (!TITLE_OPEN_RECORDED.get()) {
            return;
        }

        int replacement = REPLACEMENT_OPENINGS.incrementAndGet();
        record("startup_screen_replacement_open", "replacement=" + replacement + " screen=" + screen.getClass().getName());
    }

    public static void markScreenInitPost(Screen screen) {
        if (!awaitPresentedEndpoint() || screen == null || !TITLE_OPEN_RECORDED.get()) {
            return;
        }
        if (screen instanceof TitleScreen) {
            recordOnce(TITLE_INIT_RECORDED, "title_init_post", "screen=" + screen.getClass().getName());
        } else {
            record("startup_screen_init_post", "screen=" + screen.getClass().getName());
        }
    }

    public static void markActiveScreenRenderReturn(Screen screen) {
        if (!awaitPresentedEndpoint() || screen == null || !TITLE_OPEN_RECORDED.get()) {
            return;
        }
        String className = screen.getClass().getName();
        recordOnce(UI_RENDER_RECORDED, "startup_ui_render_return", "screen=" + className);
        RENDERED_SCREEN_ON_THREAD.set(className);
    }

    /** Called only from the injection immediately after stock Window.updateDisplay(). */
    public static void onWindowPresentReturn() {
        if (!awaitPresentedEndpoint()) {
            return;
        }
        String renderedScreen = RENDERED_SCREEN_ON_THREAD.get();
        if (renderedScreen == null) {
            return;
        }
        RENDERED_SCREEN_ON_THREAD.remove();
        if (!UI_PRESENT_RECORDED.compareAndSet(false, true)) {
            return;
        }

        record(PRESENTED_ENDPOINT,
                "screen=" + renderedScreen + " replacements=" + REPLACEMENT_OPENINGS.get()
                        + " boundary=first_updateDisplay_return_after_RenderFrameEvent_Post");

        // Exact-pack benchmark mode normally exits at ScreenEvent.Opening(TitleScreen). For this diagnostic endpoint
        // only, ClientStartupHooks defers that synthetic stop until the first real active screen has rendered and the
        // following stock display update has returned on the same render thread.
        if (StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }

    private static void recordOnce(AtomicBoolean guard, String phase, String detail) {
        if (!awaitPresentedEndpoint() || !guard.compareAndSet(false, true)) {
            return;
        }
        record(phase, detail);
    }

    private static void record(String phase, String detail) {
        RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                phase, -1L, "boot_optim", null, 1L, detail);
    }

    private static boolean traceEnabled() {
        String mode = System.getProperty(MODE_PROPERTY, "off");
        return mode != null && !mode.isBlank() && !"off".equalsIgnoreCase(mode.trim());
    }
}
