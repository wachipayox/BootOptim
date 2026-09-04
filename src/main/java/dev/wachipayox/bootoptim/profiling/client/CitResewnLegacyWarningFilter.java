package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.message.Message;
import org.slf4j.Logger;

/**
 * Diagnostic-only ceiling test for the exact pack's CITResewn legacy-name warning storm.
 *
 * <p>CITResewn is a Fabric mod loaded through Connector, so its classes do not exist when
 * BootOptim's normal Mixin configuration is prepared. The diagnostic therefore waits until
 * NeoForge common setup, after Connector has invoked Fabric client entrypoints, then finds the
 * already-created core Log4j logger named exactly {@code CITResewn}. A filter is attached directly
 * to that logger instance, avoiding assumptions about BootOptim's own LoggerContext.</p>
 *
 * <p>Only ERROR messages beginning exactly with the observed CITResewn prefix for the legacy
 * {@code nbt.display.Name} compatibility diagnostic are denied. CIT parsing, resource enumeration,
 * model loading, rule activation and resource results remain stock. The diagnostic disarms at the
 * first title screen.</p>
 */
public final class CitResewnLegacyWarningFilter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TARGET_LOGGER = "CITResewn";
    private static final String MESSAGE_PREFIX = "[citresewn] Using legacy nbt.display.Name";
    public static final long EXPECTED_SUPPRESSION_COUNT = 7_920L;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentCitLegacyWarningFilter", "false"));
    private static final AtomicLong SUPPRESSED = new AtomicLong();

    private static volatile boolean armed;
    private static volatile boolean setupAttempted;
    private static Filter filter;

    private CitResewnLegacyWarningFilter() {
    }

    public static void install(IEventBus modEventBus) {
        if (!ENABLED) {
            return;
        }
        modEventBus.addListener(CitResewnLegacyWarningFilter::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(CitResewnLegacyWarningFilter::onScreenOpening);
        LOGGER.info("BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=waiting interception=direct_citresewn_logger");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        if (setupAttempted) {
            return;
        }
        setupAttempted = true;
        installOnExactLogger();
    }

    private static void installOnExactLogger() {
        try {
            if (!(LogManager.getFactory() instanceof Log4jContextFactory factory)) {
                LOGGER.warn(
                        "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=unavailable reason=non_core_factory factory={}",
                        LogManager.getFactory().getClass().getName());
                return;
            }

            List<org.apache.logging.log4j.core.Logger> matches = new ArrayList<>();
            for (LoggerContext context : factory.getSelector().getLoggerContexts()) {
                if (context.hasLogger(TARGET_LOGGER)) {
                    matches.add(context.getLogger(TARGET_LOGGER));
                }
            }

            if (matches.size() != 1) {
                LOGGER.warn(
                        "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=unavailable reason=logger_context_match_count matches={} contexts={}",
                        matches.size(),
                        factory.getSelector().getLoggerContexts().size());
                return;
            }

            org.apache.logging.log4j.core.Logger target = matches.getFirst();
            Filter selectedFilter = new AbstractFilter() {
                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        String message,
                        Object... params) {
                    return bootoptim$result(level, message);
                }

                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        Object message,
                        Throwable throwable) {
                    return bootoptim$result(level, message == null ? null : message.toString());
                }

                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        Message message,
                        Throwable throwable) {
                    return bootoptim$result(level, bootoptim$messageText(message));
                }

                @Override
                public Result filter(LogEvent event) {
                    if (event == null) {
                        return Result.NEUTRAL;
                    }
                    return bootoptim$result(event.getLevel(), bootoptim$messageText(event.getMessage()));
                }
            };

            selectedFilter.start();
            target.addFilter(selectedFilter);
            filter = selectedFilter;
            armed = true;

            LOGGER.info(
                    "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed interception=direct_citresewn_logger logger={} context={} expected={}",
                    target.getName(),
                    target.getContext().getName(),
                    EXPECTED_SUPPRESSION_COUNT);
        } catch (RuntimeException ex) {
            LOGGER.warn("BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=unavailable reason=install_failure", ex);
        }
    }

    private static Filter.Result bootoptim$result(Level level, String message) {
        if (!armed || level != Level.ERROR || message == null || !message.startsWith(MESSAGE_PREFIX)) {
            return Filter.Result.NEUTRAL;
        }
        SUPPRESSED.incrementAndGet();
        return Filter.Result.DENY;
    }

    private static String bootoptim$messageText(Message message) {
        if (message == null) {
            return null;
        }
        String format = message.getFormat();
        if (format != null && format.startsWith(MESSAGE_PREFIX)) {
            return format;
        }
        return message.getFormattedMessage();
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen) || !armed) {
            return;
        }
        armed = false;
        if (filter != null) {
            filter.stop();
        }
        LOGGER.info(
                "BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=complete reason=title_screen suppressed={} expected={}",
                SUPPRESSED.get(),
                EXPECTED_SUPPRESSION_COUNT);
    }
}
