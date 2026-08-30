package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Temporary, narrowly-scoped diagnostics for loading overlays that remain visible after the title screen is live.
 *
 * The listener is installed only when Drippy Loading Screen is present and becomes an immediate no-op once the
 * initial title/overlay lifecycle has been classified. It intentionally does not dismiss or mutate the overlay.
 */
public final class ClientOverlayDiagnostics {
    private static final String DRIPPY_MOD_ID = "drippyloadingscreen";
    private static final int SNAPSHOT_TICKS = 20;
    private static final int STALE_TICKS = 200;

    private static boolean installed;
    private static boolean titleSeen;
    private static boolean finished;
    private static int ticksAfterTitle;
    private static String lastOverlayClass;

    private ClientOverlayDiagnostics() {
    }

    public static void install() {
        if (installed || !ModList.get().isLoaded(DRIPPY_MOD_ID)) {
            return;
        }

        installed = true;
        NeoForge.EVENT_BUS.addListener(ClientOverlayDiagnostics::onClientTick);
        logger().info("BOOTOPTIM_OVERLAY phase=diagnostics_installed target=drippyloadingscreen");
    }

    private static void onClientTick(ClientTickEvent.Pre event) {
        if (finished) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!titleSeen) {
            if (!(minecraft.screen instanceof TitleScreen)) {
                return;
            }

            titleSeen = true;
            ticksAfterTitle = 0;
            lastOverlayClass = className(minecraft.getOverlay());
            logger().info(
                    "BOOTOPTIM_OVERLAY phase=title_screen_detected overlay={} screen={}",
                    lastOverlayClass,
                    className(minecraft.screen));
            return;
        }

        ticksAfterTitle++;
        String overlayClass = className(minecraft.getOverlay());
        if (!Objects.equals(lastOverlayClass, overlayClass)) {
            logger().info(
                    "BOOTOPTIM_OVERLAY phase=overlay_transition ticks_after_title={} from={} to={} screen={}",
                    ticksAfterTitle,
                    lastOverlayClass,
                    overlayClass,
                    className(minecraft.screen));
            lastOverlayClass = overlayClass;
        }

        if (minecraft.getOverlay() == null) {
            logger().info(
                    "BOOTOPTIM_OVERLAY phase=overlay_cleared ticks_after_title={} screen={}",
                    ticksAfterTitle,
                    className(minecraft.screen));
            finished = true;
            return;
        }

        if (ticksAfterTitle == SNAPSHOT_TICKS) {
            logger().info(
                    "BOOTOPTIM_OVERLAY phase=post_title_snapshot ticks_after_title={} overlay={} screen={}",
                    ticksAfterTitle,
                    overlayClass,
                    className(minecraft.screen));
        }

        if (ticksAfterTitle >= STALE_TICKS) {
            logger().warn(
                    "BOOTOPTIM_OVERLAY phase=stale_overlay_suspected ticks_after_title={} overlay={} screen={}",
                    ticksAfterTitle,
                    overlayClass,
                    className(minecraft.screen));
            finished = true;
        }
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static Logger logger() {
        return LogUtils.getLogger();
    }
}
