package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Diagnostic-only ceiling test for the exact pack's CITResewn legacy-name warning storm.
 *
 * <p>The interception point is CITResewn's own {@code logWarnLoading(String)} helper, immediately
 * before it calls its {@code CITResewn} Log4j logger at ERROR. Only the known legacy-name message
 * is cancelled. CIT parsing, resource enumeration, model loading, rule activation and resource
 * results remain stock. The diagnostic disarms at the first title screen.</p>
 */
public final class CitResewnLegacyWarningFilter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MESSAGE_PREFIX = "Using legacy nbt.display.Name";
    public static final long EXPECTED_SUPPRESSION_COUNT = 7_920L;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentCitLegacyWarningFilter", "false"));
    private static final AtomicLong SUPPRESSED = new AtomicLong();

    private static volatile boolean armed;

    private CitResewnLegacyWarningFilter() {
    }

    public static void install() {
        if (!ENABLED || armed) {
            return;
        }
        armed = true;
        NeoForge.EVENT_BUS.addListener(CitResewnLegacyWarningFilter::onScreenOpening);
        LOGGER.info(
                "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed interception=citresewn_logWarnLoading expected={}",
                EXPECTED_SUPPRESSION_COUNT);
    }

    /** Called only from the optional CITResewn compatibility mixin. */
    public static boolean shouldSuppress(String message) {
        if (!armed || message == null || !message.startsWith(MESSAGE_PREFIX)) {
            return false;
        }
        SUPPRESSED.incrementAndGet();
        return true;
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen) || !armed) {
            return;
        }
        armed = false;
        LOGGER.info(
                "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=complete reason=title_screen suppressed={} expected={}",
                SUPPRESSED.get(),
                EXPECTED_SUPPRESSION_COUNT);
    }
}
