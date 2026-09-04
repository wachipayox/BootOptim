package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.slf4j.Logger;

/**
 * Diagnostic-only ceiling test for the exact pack's CITResewn legacy-name warning storm.
 *
 * <p>The candidate suppresses only CITResewn ERROR events whose message is the repeated
 * "Using legacy nbt.display.Name" compatibility diagnostic. CIT parsing, resource enumeration,
 * model loading, rule activation and every resource result stay stock. The filter is removed at
 * the first title screen and reports the number of denied log events.</p>
 */
public final class CitResewnLegacyWarningFilter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MESSAGE_FRAGMENT = "Using legacy nbt.display.Name";
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentCitLegacyWarningFilter", "false"));
    private static final AtomicLong SUPPRESSED = new AtomicLong();

    private static LoggerContext loggerContext;
    private static Configuration configuration;
    private static Filter filter;
    private static boolean installed;

    private CitResewnLegacyWarningFilter() {
    }

    public static void install() {
        if (!ENABLED || installed) {
            return;
        }

        try {
            Object context = LogManager.getContext(false);
            if (!(context instanceof LoggerContext coreContext)) {
                LOGGER.warn(
                        "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=unavailable reason=non_core_context context={}",
                        context == null ? "null" : context.getClass().getName());
                return;
            }

            LoggerContext selectedContext = coreContext;
            Configuration selectedConfiguration = selectedContext.getConfiguration();
            Filter selectedFilter = new AbstractFilter() {
                @Override
                public Result filter(LogEvent event) {
                    if (bootoptim$matches(event)) {
                        SUPPRESSED.incrementAndGet();
                        return Result.DENY;
                    }
                    return Result.NEUTRAL;
                }
            };

            selectedFilter.start();
            selectedConfiguration.addFilter(selectedFilter);
            selectedContext.updateLoggers();

            loggerContext = selectedContext;
            configuration = selectedConfiguration;
            filter = selectedFilter;
            installed = true;
            NeoForge.EVENT_BUS.addListener(CitResewnLegacyWarningFilter::onScreenOpening);
            LOGGER.info("BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=installed");
        } catch (RuntimeException ex) {
            LOGGER.warn("BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=unavailable reason=install_failure", ex);
        }
    }

    private static boolean bootoptim$matches(LogEvent event) {
        if (event == null || event.getLevel() != Level.ERROR) {
            return false;
        }

        String loggerName = event.getLoggerName();
        if (loggerName == null || !loggerName.toLowerCase(Locale.ROOT).contains("citresewn")) {
            return false;
        }

        Message message = event.getMessage();
        if (message == null) {
            return false;
        }

        String format = message.getFormat();
        if (format != null && format.contains(MESSAGE_FRAGMENT)) {
            return true;
        }

        String formatted = message.getFormattedMessage();
        return formatted != null && formatted.contains(MESSAGE_FRAGMENT);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }
        uninstallAndReport("title_screen");
    }

    private static void uninstallAndReport(String reason) {
        if (!installed) {
            return;
        }

        long suppressed = SUPPRESSED.get();
        try {
            configuration.removeFilter(filter);
            filter.stop();
            loggerContext.updateLoggers();
        } catch (RuntimeException ex) {
            LOGGER.warn("BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=cleanup_failed reason={}", reason, ex);
        } finally {
            installed = false;
            configuration = null;
            filter = null;
            loggerContext = null;
        }

        LOGGER.info(
                "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=complete reason={} suppressed={}",
                reason,
                suppressed);
    }
}
