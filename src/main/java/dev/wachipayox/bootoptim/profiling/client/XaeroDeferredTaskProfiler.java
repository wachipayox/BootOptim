package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Diagnostic-only stack sampler for Xaero World Map's FML deferred startup task.
 *
 * <p>An early ModLauncher transformer records the exact stock DeferredWorkQueue owner/runnable
 * boundary in synthetic volatile fields on DeferredWorkQueue itself. This class only observes those
 * fields and samples the recorded executing thread; it never wraps or redirects the Runnable.</p>
 */
public final class XaeroDeferredTaskProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFERRED_WORK_QUEUE = "net.neoforged.fml.DeferredWorkQueue";
    private static final String START_FIELD = "bootoptim$xaeroStartNanos";
    private static final String END_FIELD = "bootoptim$xaeroEndNanos";
    private static final String THREAD_FIELD = "bootoptim$xaeroThreadId";
    private static final String STATE_FIELD = "bootoptim$xaeroBoundaryState";
    private static final long WATCH_INTERVAL_MS = 2L;
    private static final long SAMPLE_INTERVAL_MS = 5L;
    private static final long MAX_ACTIVE_MS = 5_000L;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileXaeroDeferredTask", "false"));

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean FINISHED = new AtomicBoolean();

    private XaeroDeferredTaskProfiler() {
    }

    public static void install() {
        if (!ENABLED || !INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(XaeroDeferredTaskProfiler::onScreenOpening);
        try {
            Class<?> deferredWorkQueue = Class.forName(DEFERRED_WORK_QUEUE);
            Field start = deferredWorkQueue.getField(START_FIELD);
            Field end = deferredWorkQueue.getField(END_FIELD);
            Field thread = deferredWorkQueue.getField(THREAD_FIELD);
            Field state = deferredWorkQueue.getField(STATE_FIELD);

            Thread watcher = new Thread(
                    () -> watchBoundary(start, end, thread, state),
                    "BootOptim-XaeroDeferredWatcher");
            watcher.setDaemon(true);
            watcher.start();
            LOGGER.info(
                    "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=installed boundary=early_deferred_work_queue_transform watch_ms={} sample_ms={}",
                    WATCH_INTERVAL_MS,
                    SAMPLE_INTERVAL_MS);
        } catch (ReflectiveOperationException | LinkageError ex) {
            FINISHED.set(true);
            LOGGER.warn(
                    "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=unavailable reason=boundary_fields_missing boundary=early_deferred_work_queue_transform",
                    ex);
        }
    }

    private static void watchBoundary(Field startField, Field endField, Field threadField, Field stateField) {
        try {
            while (!STOP_REQUESTED.get() && !FINISHED.get()) {
                int state = stateField.getInt(null);
                if (state == 1 || state == 2) {
                    sampleObservedBoundary(startField, endField, threadField, stateField, state);
                    return;
                }
                Thread.sleep(WATCH_INTERVAL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finishInvalid("watcher_interrupted", 0L, 0, Map.of(), Map.of(), "unknown");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            FINISHED.set(true);
            LOGGER.warn(
                    "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=unavailable reason=boundary_read_failure boundary=early_deferred_work_queue_transform",
                    ex);
        }
    }

    private static void sampleObservedBoundary(
            Field startField,
            Field endField,
            Field threadField,
            Field stateField,
            int initialState) throws ReflectiveOperationException, InterruptedException {
        long startNanos = startField.getLong(null);
        long threadId = threadField.getLong(null);
        Thread target = findThread(threadId);
        String threadName = target == null ? "missing_thread_" + threadId : target.getName();

        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=sampling boundary=before_runnable owner=xaeroworldmap thread={} thread_id={} initial_state={}",
                threadName,
                threadId,
                initialState);

        Map<String, Integer> leafCounts = new HashMap<>();
        Map<String, Integer> xaeroCounts = new HashMap<>();
        int samples = 0;
        long deadline = startNanos + TimeUnit.MILLISECONDS.toNanos(MAX_ACTIVE_MS);

        int state = initialState;
        while (state == 1 && System.nanoTime() < deadline) {
            if (target == null || !target.isAlive()) {
                target = findThread(threadId);
                if (target != null) {
                    threadName = target.getName();
                }
            }

            if (target != null) {
                StackTraceElement[] stack = target.getStackTrace();
                if (stack.length > 0) {
                    samples++;
                    StackTraceElement leaf = selectLeaf(stack);
                    if (leaf != null) {
                        leafCounts.merge(frameKey(leaf), 1, Integer::sum);
                    }
                    StackTraceElement xaeroFrame = selectXaeroFrame(stack);
                    if (xaeroFrame != null) {
                        xaeroCounts.merge(frameKey(xaeroFrame), 1, Integer::sum);
                    }
                }
            }

            Thread.sleep(SAMPLE_INTERVAL_MS);
            state = stateField.getInt(null);
        }

        long endNanos = endField.getLong(null);
        if (state == 2 && startNanos > 0L && endNanos >= startNanos) {
            finishValid(startNanos, endNanos, samples, leafCounts, xaeroCounts, threadName);
            return;
        }

        long fallbackEnd = System.nanoTime();
        finishInvalid(
                state == 1 ? "boundary_stuck_timeout" : "boundary_invalid_state_" + state,
                Math.max(0L, fallbackEnd - startNanos),
                samples,
                leafCounts,
                xaeroCounts,
                threadName);
    }

    private static Thread findThread(long threadId) {
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            if (thread.threadId() == threadId) {
                return thread;
            }
        }
        return null;
    }

    private static void finishValid(
            long startNanos,
            long endNanos,
            int samples,
            Map<String, Integer> leafCounts,
            Map<String, Integer> xaeroCounts,
            String threadName) {
        if (!FINISHED.compareAndSet(false, true)) {
            return;
        }
        long wallMs = TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=after_runnable_owner_clear boundary=early_deferred_work_queue_transform owner=xaeroworldmap thread={} wall_ms={} samples={} top_leaf={} top_xaero={}",
                threadName,
                wallMs,
                samples,
                top(leafCounts, 12),
                top(xaeroCounts, 12));
    }

    private static void finishInvalid(
            String reason,
            long wallNanos,
            int samples,
            Map<String, Integer> leafCounts,
            Map<String, Integer> xaeroCounts,
            String threadName) {
        if (!FINISHED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason={} boundary=early_deferred_work_queue_transform owner=xaeroworldmap thread={} wall_ms={} samples={} top_leaf={} top_xaero={}",
                reason,
                threadName,
                TimeUnit.NANOSECONDS.toMillis(Math.max(0L, wallNanos)),
                samples,
                top(leafCounts, 12),
                top(xaeroCounts, 12));
    }

    private static StackTraceElement selectLeaf(StackTraceElement[] stack) {
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

    private static StackTraceElement selectXaeroFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("xaero.")) {
                return frame;
            }
        }
        return null;
    }

    private static String frameKey(StackTraceElement frame) {
        return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private static String top(Map<String, Integer> counts, int limit) {
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

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen) || FINISHED.get()) {
            return;
        }
        STOP_REQUESTED.set(true);
        if (FINISHED.compareAndSet(false, true)) {
            LOGGER.info(
                    "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=title_without_observation boundary=early_deferred_work_queue_transform samples=0");
        }
    }
}
