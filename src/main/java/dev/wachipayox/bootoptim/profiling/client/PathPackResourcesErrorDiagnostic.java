package dev.wachipayox.bootoptim.profiling.client;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.message.Message;

/** Diagnostic-only synchronous observer. Never filters or rewrites an event. */
public final class PathPackResourcesErrorDiagnostic {
    private static final String TARGET_LOGGER = "net.minecraft.server.packs.PathPackResources";
    private static final String TARGET_FORMAT = "Invalid path {}: {}";
    private static final String TARGET_RENDERED = "Invalid path : Invalid path ''";

    private PathPackResourcesErrorDiagnostic() {}

    public static void install() {
        if (!(LogManager.getFactory() instanceof Log4jContextFactory factory)) return;
        int installed = 0;
        for (LoggerContext context : factory.getSelector().getLoggerContexts()) {
            org.apache.logging.log4j.core.Logger target = context.getLogger(TARGET_LOGGER);
            Filter filter = new AbstractFilter() {
                @Override
                public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker,
                                     String message, Object... params) {
                    if (logger != null && TARGET_LOGGER.equals(logger.getName()) && level == Level.ERROR
                            && TARGET_FORMAT.equals(message) && params != null && params.length >= 2
                            && String.valueOf(params[0]).isEmpty() && "Invalid path ''".equals(String.valueOf(params[1]))) {
                        emit();
                    }
                    return Result.NEUTRAL;
                }

                @Override
                public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker,
                                     Message message, Throwable throwable) {
                    if (logger != null && TARGET_LOGGER.equals(logger.getName()) && level == Level.ERROR
                            && message != null && TARGET_RENDERED.equals(message.getFormattedMessage())) {
                        emit();
                    }
                    return Result.NEUTRAL;
                }
            };
            filter.start();
            target.addFilter(filter);
            installed++;
        }
        System.out.println("[BootOptim PathPack diagnostic] installed contexts=" + installed);
    }

    private static void emit() {
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2).limit(24).map(StackTraceElement::toString)
                .collect(Collectors.joining(" <- "));
        System.out.println("[BootOptim PathPack diagnostic] callerStack=" + stack);
    }
}
