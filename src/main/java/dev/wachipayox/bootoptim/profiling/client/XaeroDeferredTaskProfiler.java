package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.slf4j.Logger;

/**
 * Diagnostic-only stack sampler for Xaero World Map's slow NeoForge deferred task.
 *
 * <p>The exact pack reports a roughly one-second {@code xaeroworldmap} deferred task on the
 * Render thread immediately after Xaero World Map logs its Stage 2/2 marker. Xaero's source is
 * not controlled by this project, so this profiler observes that existing interval without
 * skipping, reordering or wrapping the task itself.</p>
 */
public final class XaeroDeferredTaskProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STAGE_MESSAGE = "Loading Xaero's World Map - Stage 2/2";
    private static final String DEFERRED_FORMAT = "Mod '{}' took {} to run a deferred task.";
    private static final String DEFERRED_ID = "xaeroworldmap";
    private static final long SAMPLE_INTERVAL_MS = 5L;
    private static final long MAX_SAMPLE_MS = 5_000L;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileXaeroDeferredTask", "false"));

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean FINISHED = new AtomicBoolean();

    private static volatile LoggerContext loggerContext;
    private static volatile Configuration configuration;
    private static volatile Filter filter;
    private static volatile Thread targetThread;
    private static volatile long startNanos;
    private static volatile long endNanos;
    private static volatile String completionReason;

    private XaeroDeferredTaskProfiler() {
    }

    public static void install() {
        if (!ENABLED || !INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            Object context = LogManager.getContext(false);
            if (!(context instanceof LoggerContext coreContext)) {
                INSTALLED.set(false);
                LOGGER.warn(
                        "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=unavailable reason=non_core_context context={}",
                        context == null ? "null" : context.getClass().getName());
                return;
            }

            LoggerContext selectedContext = coreContext;
            Configuration selectedConfiguration = selectedContext.getConfiguration();
            Filter selectedFilter = new AbstractFilter() {
                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        String message,
                        Object... params) {
                    bootoptim$observe(
                            logger == null ? null : logger.getName(),
                            message,
                            params);
                    return Result.NEUTRAL;
                }

                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        Object message,
                        Throwable throwable) {
                    bootoptim$observe(
                            logger == null ? null : logger.getName(),
                            message == null ? null : message.toString(),
                            null);
                    return Result.NEUTRAL;
                }

                @Override
                public Result filter(
                        org.apache.logging.log4j.core.Logger logger,
                        Level level,
                        Marker marker,
                        Message message,
                        Throwable throwable) {
                    bootoptim$observe(
                            logger == null ? null : logger.getName(),
                            bootoptim$messageText(message),
                            null);
                    return Result.NEUTRAL;
                }

                @Override
                public Result filter(LogEvent event) {
                    if (event != null) {
                        bootoptim$observe(
                                event.getLoggerName(),
                                bootoptim$messageText(event.getMessage()),
                                null);
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
            NeoForge.EVENT_BUS.addListener(XaeroDeferredTaskProfiler::onScreenOpening);
            LOGGER.info("BOOTOPTIM_XAERO_DEFERRED_PROFILE status=installed sample_ms={}", SAMPLE_INTERVAL_MS);
        } catch (RuntimeException ex) {
            INSTALLED.set(false);
            LOGGER.warn("BOOTOPTIM_XAERO_DEFERRED_PROFILE status=unavailable reason=install_failure", ex);
        }
    }

    private static void bootoptim$observe(String loggerName, String message, Object[] params) {
        if (message == null || FINISHED.get()) {
            return;
        }

        if (!ACTIVE.get()
                && loggerName != null
                && loggerName.startsWith("xaero.map")
                && message.contains(STAGE_MESSAGE)) {
            bootoptim$startSampling(Thread.currentThread());
            return;
        }

        if (!ACTIVE.get()) {
            return;
        }

        boolean formattedMatch = message.contains("Mod 'xaeroworldmap' took")
                && message.contains("deferred task");
        boolean parameterizedMatch = DEFERRED_FORMAT.equals(message)
                && params != null
                && params.length > 0
                && DEFERRED_ID.equals(String.valueOf(params[0]));
        if (formattedMatch || parameterizedMatch) {
            completionReason = "deferred_task_warning";
            endNanos = System.nanoTime();
            ACTIVE.set(false);
        }
    }

    private static void bootoptim$startSampling(Thread thread) {
        if (FINISHED.get() || !ACTIVE.compareAndSet(false, true)) {
            return;
        }

        targetThread = thread;
        startNanos = System.nanoTime();
        endNanos = 0L;
        completionReason = null;
        Thread sampler = new Thread(XaeroDeferredTaskProfiler::bootoptim$sampleLoop, "BootOptim-XaeroDeferredSampler");
        sampler.setDaemon(true);
        sampler.start();
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=sampling thread={} thread_id={}",
                thread.getName(),
                thread.threadId());
    }

    private static void bootoptim$sampleLoop() {
        Map<String, Integer> leafCounts = new HashMap<>();
        Map<String, Integer> xaeroCounts = new HashMap<>();
        int samples = 0;
        long deadline = startNanos + TimeUnit.MILLISECONDS.toNanos(MAX_SAMPLE_MS);

        while (ACTIVE.get() && System.nanoTime() < deadline) {
            Thread currentTarget = targetThread;
            if (currentTarget == null) {
                break;
            }

            StackTraceElement[] stack = currentTarget.getStackTrace();
            if (stack.length > 0) {
                samples++;
                StackTraceElement leaf = bootoptim$selectLeaf(stack);
                if (leaf != null) {
                    leafCounts.merge(bootoptim$frameKey(leaf), 1, Integer::sum);
                }
                StackTraceElement xaeroFrame = bootoptim$selectXaeroFrame(stack);
                if (xaeroFrame != null) {
                    xaeroCounts.merge(bootoptim$frameKey(xaeroFrame), 1, Integer::sum);
                }
            }

            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                completionReason = "sampler_interrupted";
                break;
            }
        }

        if (ACTIVE.getAndSet(false)) {
            endNanos = System.nanoTime();
            if (completionReason == null) {
                completionReason = "timeout";
            }
        }
        if (endNanos == 0L) {
            endNanos = System.nanoTime();
        }

        FINISHED.set(true);
        bootoptim$removeFilter();

        long wallMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, endNanos - startNanos));
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason={} thread={} wall_ms={} samples={} top_leaf={} top_xaero={}",
                completionReason == null ? "unknown" : completionReason,
                targetThread == null ? "unknown" : targetThread.getName(),
                wallMs,
                samples,
                bootoptim$top(leafCounts, 12),
                bootoptim$top(xaeroCounts, 12));
    }

    private static StackTraceElement bootoptim$selectLeaf(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.startsWith("org.apache.logging.log4j.")
                    || className.startsWith("org.slf4j.")
                    || className.startsWith("dev.wachipayox.bootoptim.profiling.client.XaeroDeferredTaskProfiler")) {
                continue;
            }
            return frame;
        }
        return null;
    }

    private static StackTraceElement bootoptim$selectXaeroFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("xaero.")) {
                return frame;
            }
        }
        return null;
    }

    private static String bootoptim$frameKey(StackTraceElement frame) {
        return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private static String bootoptim$top(Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("none");
    }

    private static String bootoptim$messageText(Message message) {
        return message == null ? null : message.getFormattedMessage();
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen) || FINISHED.get()) {
            return;
        }
        completionReason = "title_without_observation";
        FINISHED.set(true);
        ACTIVE.set(false);
        bootoptim$removeFilter();
        LOGGER.info("BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=title_without_observation samples=0");
    }

    private static void bootoptim$removeFilter() {
        if (!INSTALLED.compareAndSet(true, false)) {
            return;
        }
        try {
            Configuration selectedConfiguration = configuration;
            Filter selectedFilter = filter;
            LoggerContext selectedContext = loggerContext;
            if (selectedConfiguration != null && selectedFilter != null) {
                selectedConfiguration.removeFilter(selectedFilter);
                selectedFilter.stop();
            }
            if (selectedContext != null) {
                selectedContext.updateLoggers();
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("BOOTOPTIM_XAERO_DEFERRED_PROFILE status=cleanup_failed", ex);
        } finally {
            configuration = null;
            filter = null;
            loggerContext = null;
        }
    }
}
