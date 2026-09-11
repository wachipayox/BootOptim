package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Suppresses one user-authorized CITResewn compatibility-warning family during initial startup.
 *
 * <p>The matcher is deliberately pinned to the exact historical event shape exercised by the
 * exact-pack fixture: logger {@code CITResewn}, Log4j {@code ERROR} severity, the legacy
 * {@code nbt.display.Name} message, an OptiFine CIT properties path, and the exact source-pack
 * descriptor {@code file/Glowing Trim Armors v5.0.zip}. Any drift fails open and remains visible.
 * Parsing, conversion, resource lookup/order, CIT state and model behavior are untouched.</p>
 */
public final class CitResewnGlowingTrimLegacyWarningSuppression {
    public static final String PROPERTY = "boot_optim.citresewnGlowingTrimLegacyWarningSuppression";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TARGET_LOGGER = "CITResewn";
    private static final String MESSAGE_PREFIX = "[citresewn] Using legacy nbt.display.Name @L";
    private static final String PATH_MARKER = " in minecraft:optifine/cit/";
    private static final String PACK_SUFFIX = " from file/Glowing Trim Armors v5.0.zip";
    private static final long EXPECTED_EXACT_PACK_COUNT = 7_920L;
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    private static final AtomicLong SUPPRESSED = new AtomicLong();
    private static final AtomicBoolean SUMMARY_LOGGED = new AtomicBoolean();

    private static volatile boolean armed;
    private static volatile boolean setupAttempted;
    private static Filter filter;

    private CitResewnGlowingTrimLegacyWarningSuppression() {}

    public static void install(IEventBus modEventBus) {
        if (!ENABLED) {
            return;
        }
        modEventBus.addListener(CitResewnGlowingTrimLegacyWarningSuppression::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(CitResewnGlowingTrimLegacyWarningSuppression::onScreenOpening);
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
            if (!matcherContractHolds()) {
                LOGGER.warn("BOOTOPTIM_CIT_LEGACY_WARNING_SUPPRESSION status=unavailable reason=matcher_contract");
                return;
            }
            if (!(LogManager.getFactory() instanceof Log4jContextFactory factory)) {
                LOGGER.warn(
                        "BOOTOPTIM_CIT_LEGACY_WARNING_SUPPRESSION status=unavailable reason=non_core_factory factory={}",
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
                        "BOOTOPTIM_CIT_LEGACY_WARNING_SUPPRESSION status=unavailable reason=logger_context_match_count matches={} contexts={}",
                        matches.size(), factory.getSelector().getLoggerContexts().size());
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
                    return result(logger == null ? null : logger.getName(), level, message);
                }

                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        Object message,
                        Throwable throwable) {
                    return result(
                            logger == null ? null : logger.getName(),
                            level,
                            message == null ? null : message.toString());
                }

                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        Message message,
                        Throwable throwable) {
                    return result(
                            logger == null ? null : logger.getName(), level, messageText(message));
                }

                @Override
                public Result filter(LogEvent event) {
                    if (event == null) {
                        return Result.NEUTRAL;
                    }
                    return result(event.getLoggerName(), event.getLevel(), messageText(event.getMessage()));
                }
            };

            selectedFilter.start();
            target.addFilter(selectedFilter);
            filter = selectedFilter;
            armed = true;
            LOGGER.info(
                    "BOOTOPTIM_CIT_LEGACY_WARNING_SUPPRESSION status=armed logger={} context={} matcher=exact_glowing_trim_pack expected_exact_pack={} contract=passed",
                    target.getName(), target.getContext().getName(), EXPECTED_EXACT_PACK_COUNT);
        } catch (RuntimeException ex) {
            LOGGER.warn("BOOTOPTIM_CIT_LEGACY_WARNING_SUPPRESSION status=unavailable reason=install_failure", ex);
        }
    }

    private static Filter.Result result(String loggerName, Level level, String message) {
        if (!armed || !isExactTarget(loggerName, level, message)) {
            return Filter.Result.NEUTRAL;
        }
        SUPPRESSED.incrementAndGet();
        return Filter.Result.DENY;
    }

    static boolean isExactTarget(String loggerName, Level level, String message) {
        if (!TARGET_LOGGER.equals(loggerName) || level != Level.ERROR || message == null) {
            return false;
        }
        if (!message.startsWith(MESSAGE_PREFIX) || !message.endsWith(PACK_SUFFIX)) {
            return false;
        }
        int pathStart = message.indexOf(PATH_MARKER, MESSAGE_PREFIX.length());
        if (pathStart < 0) {
            return false;
        }
        int pathValueStart = pathStart + " in ".length();
        int pathEnd = message.length() - PACK_SUFFIX.length();
        if (pathValueStart >= pathEnd) {
            return false;
        }
        String resourcePath = message.substring(pathValueStart, pathEnd);
        return resourcePath.startsWith("minecraft:optifine/cit/") && resourcePath.endsWith(".properties");
    }

    private static boolean matcherContractHolds() {
        String target = "[citresewn] Using legacy nbt.display.Name @L4 in minecraft:optifine/cit/items/armor_items/diamond/amethyst/a1/dboots.properties from file/Glowing Trim Armors v5.0.zip";
        return isExactTarget(TARGET_LOGGER, Level.ERROR, target)
                && !isExactTarget(TARGET_LOGGER, Level.WARN, target)
                && !isExactTarget("OtherLogger", Level.ERROR, target)
                && !isExactTarget(TARGET_LOGGER, Level.ERROR, target.replace("Glowing Trim Armors v5.0.zip", "Other Pack.zip"))
                && !isExactTarget(TARGET_LOGGER, Level.ERROR,
                        "[citresewn] Failed to parse CIT in minecraft:optifine/cit/broken.properties from file/Glowing Trim Armors v5.0.zip")
                && !isExactTarget(TARGET_LOGGER, Level.ERROR,
                        "[citresewn] Using legacy nbt.display.Lore @L4 in minecraft:optifine/cit/items/example.properties from file/Glowing Trim Armors v5.0.zip");
    }

    private static String messageText(Message message) {
        if (message == null) {
            return null;
        }
        String format = message.getFormat();
        if (format != null && format.startsWith("[citresewn]")) {
            return format;
        }
        return message.getFormattedMessage();
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen) || !armed || !SUMMARY_LOGGED.compareAndSet(false, true)) {
            return;
        }

        armed = false;
        Filter installedFilter = filter;
        filter = null;
        if (installedFilter != null) {
            // Log4j 2.24.x exposes Logger.addFilter but no symmetric public removeFilter API.
            // Stopping plus armed=false makes the attached filter permanently NEUTRAL; BootOptim drops
            // its own reference here. The filter captures no CIT/resource/model object or logger/context.
            installedFilter.stop();
        }

        LOGGER.info(
                "BOOTOPTIM_CIT_LEGACY_WARNING_SUPPRESSION status=summary suppressed={} target_pack=\"Glowing Trim Armors v5.0.zip\" target=legacy_nbt_display_name kill_switch=-D{}=false",
                SUPPRESSED.get(), PROPERTY);
    }
}
