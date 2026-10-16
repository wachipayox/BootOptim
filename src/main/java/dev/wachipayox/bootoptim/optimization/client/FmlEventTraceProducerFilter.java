package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.filter.Filterable;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.slf4j.Logger;

/**
 * Stops FML's per-mod, per-event {@code TRACE} messages from being built when no appender would
 * ever write them.
 *
 * <p>{@code ModContainer.acceptEvent} logs "Firing/Fired event for modid ..." at {@code TRACE} for
 * every mod and every event. NeoForge's default Log4j configuration leaves the logger level open
 * and drops these events at the appender references, so with async loggers each call still formats
 * its parameters ({@code event.toString()}) and publishes a ring-buffer event that is then
 * discarded. In the Wachiland pack this dominates render-thread samples during the post-menu
 * reload tail and the final F3+T apply (Veil posts one event per shader setup).</p>
 *
 * <p>Equivalence: the filter is installed on a Log4j configuration only after checking that a
 * {@code TRACE} event from that logger with FML's {@code LOADING} marker is rejected by every
 * reachable appender reference, appender filter and logger-config filter. Nothing that was written
 * before is dropped. A reconfiguration replaces the configuration and therefore the filter, which
 * restores stock behaviour. Event dispatch itself is untouched.</p>
 */
public final class FmlEventTraceProducerFilter {
    public static final String PROPERTY = "boot_optim.fmlEventTraceProducerFilter";
    static final String TARGET_LOGGER = "net.neoforged.fml.ModContainer";
    private static final String COMPAT_MECHANISM = "fml-event-trace-producer-filter";
    private static final String COMPAT_MOD = "neoforge";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    private static final LongAdder DENIED = new LongAdder();
    private static final AtomicBoolean SUMMARY_LOGGED = new AtomicBoolean();

    private FmlEventTraceProducerFilter() {}

    public static void install() {
        if (!ENABLED) {
            LOGGER.info("BOOTOPTIM_FML_EVENT_TRACE_FILTER status=disabled kill_switch=-D{}=false", PROPERTY);
            return;
        }
        try {
            if (!(LogManager.getFactory() instanceof Log4jContextFactory factory)) {
                CompatLog.fallback(COMPAT_MECHANISM, COMPAT_MOD,
                        "non_core_log4j_factory " + LogManager.getFactory().getClass().getName());
                return;
            }
            List<LoggerContext> contexts = new ArrayList<>();
            for (LoggerContext context : factory.getSelector().getLoggerContexts()) {
                if (context.hasLogger(TARGET_LOGGER)) {
                    contexts.add(context);
                }
            }
            if (contexts.isEmpty()) {
                CompatLog.fallback(COMPAT_MECHANISM, COMPAT_MOD, "modcontainer_logger_not_found");
                return;
            }
            int installed = 0;
            for (LoggerContext context : contexts) {
                Configuration config = context.getConfiguration();
                if (!context.getLogger(TARGET_LOGGER).isTraceEnabled()) {
                    LOGGER.info("BOOTOPTIM_FML_EVENT_TRACE_FILTER status=not_needed context={} reason=trace_already_disabled",
                            context.getName());
                    continue;
                }
                String reachable = traceReachableAppender(config);
                if (reachable != null) {
                    CompatLog.fallback(COMPAT_MECHANISM, COMPAT_MOD, "trace_written_by_appender " + reachable);
                    return;
                }
                Filter ours = new DenyTraceFilter();
                ours.start();
                Filter previous = config.getFilter();
                if (previous != null) {
                    config.removeFilter(previous);
                    config.addFilter(CompositeFilter.createFilters(new Filter[] {ours, previous}));
                } else {
                    config.addFilter(ours);
                }
                installed++;
                LOGGER.info("BOOTOPTIM_FML_EVENT_TRACE_FILTER status=armed context={} config={}",
                        context.getName(), config.getName());
            }
            if (installed > 0) {
                CompatLog.active(COMPAT_MECHANISM, COMPAT_MOD);
                NeoForge.EVENT_BUS.addListener(FmlEventTraceProducerFilter::onScreenOpening);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("BOOTOPTIM_FML_EVENT_TRACE_FILTER status=unavailable reason=install_failure", failure);
            CompatLog.fallback(COMPAT_MECHANISM, COMPAT_MOD, "install_failure " + failure.getClass().getSimpleName());
        }
    }

    /** Returns the name of an appender that would write FML's event TRACE line, or null if none would. */
    static String traceReachableAppender(Configuration config) {
        LogEvent probe = Log4jLogEvent.newBuilder()
                .setLoggerName(TARGET_LOGGER)
                .setLoggerFqcn(TARGET_LOGGER)
                .setMarker(MarkerManager.getMarker("LOADING"))
                .setLevel(Level.TRACE)
                .setMessage(new SimpleMessage("Firing event for modid boot_optim : probe"))
                .setThreadName(Thread.currentThread().getName())
                .build();
        LoggerConfig loggerConfig = config.getLoggerConfig(TARGET_LOGGER);
        while (loggerConfig != null) {
            if (!loggerConfig.isFiltered(probe)) {
                for (AppenderRef ref : loggerConfig.getAppenderRefs()) {
                    if (ref.getLevel() != null && !Level.TRACE.isMoreSpecificThan(ref.getLevel())) {
                        continue;
                    }
                    Filter refFilter = ref.getFilter();
                    if (refFilter != null && refFilter.filter(probe) == Filter.Result.DENY) {
                        continue;
                    }
                    Appender appender = config.getAppender(ref.getRef());
                    if (appender == null) {
                        continue;
                    }
                    if (appender instanceof Filterable filterable && filterable.isFiltered(probe)) {
                        continue;
                    }
                    return appender.getName();
                }
            }
            if (!loggerConfig.isAdditive()) {
                break;
            }
            loggerConfig = loggerConfig.getParent();
        }
        return null;
    }

    static boolean isTarget(org.apache.logging.log4j.core.Logger logger, Level level) {
        return level == Level.TRACE && logger != null && TARGET_LOGGER.equals(logger.getName());
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof TitleScreen && SUMMARY_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("BOOTOPTIM_FML_EVENT_TRACE_FILTER status=summary denied_until_title={} kill_switch=-D{}=false",
                    DENIED.sum(), PROPERTY);
        }
    }

    public static long deniedCount() {
        return DENIED.sum();
    }

    private static final class DenyTraceFilter extends AbstractFilter {
        private Result decide(org.apache.logging.log4j.core.Logger logger, Level level) {
            if (isTarget(logger, level)) {
                DENIED.increment();
                return Result.DENY;
            }
            return Result.NEUTRAL;
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, String msg, Object... params) {
            return decide(logger, level);
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
            return decide(logger, level);
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2) {
            return decide(logger, level);
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, Object msg, Throwable t) {
            return decide(logger, level);
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, Message msg, Throwable t) {
            return decide(logger, level);
        }
    }
}
