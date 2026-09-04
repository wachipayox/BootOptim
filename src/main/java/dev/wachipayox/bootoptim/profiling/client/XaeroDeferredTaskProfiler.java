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
import org.slf4j.Logger;

/**
 * Diagnostic-only stack sampler for Xaero World Map's FML deferred startup task.
 *
 * <p>The start/end boundary comes from FML's existing active-ModContainer transition around the
 * queued Runnable. The sampler never wraps, redirects, replaces, delays, or reorders that Runnable.</p>
 */
public final class XaeroDeferredTaskProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SAMPLE_INTERVAL_MS = 5L;
    private static final long MAX_SAMPLE_MS = 5_000L;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileXaeroDeferredTask", "false"));

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean FINISHED = new AtomicBoolean();

    private static volatile Thread targetThread;
    private static volatile long startNanos;
    private static volatile long endNanos;
    private static volatile String ownerId;
    private static volatile String completionReason;

    private XaeroDeferredTaskProfiler() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void install() {
        if (!ENABLED || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(XaeroDeferredTaskProfiler::onScreenOpening);
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=installed boundary=fml_active_container sample_ms={}",
                SAMPLE_INTERVAL_MS);
    }

    public static boolean isSamplingCurrentThread() {
        return ACTIVE.get() && targetThread == Thread.currentThread();
    }

    public static void onDeferredTaskStart(String modId) {
        if (!ENABLED || FINISHED.get() || !ACTIVE.compareAndSet(false, true)) {
            return;
        }

        ownerId = modId;
        targetThread = Thread.currentThread();
        startNanos = System.nanoTime();
        endNanos = 0L;
        completionReason = null;

        Thread sampler = new Thread(XaeroDeferredTaskProfiler::sampleLoop, "BootOptim-XaeroDeferredSampler");
        sampler.setDaemon(true);
        sampler.start();

        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=sampling boundary=before_runnable owner={} thread={} thread_id={}",
                modId,
                targetThread.getName(),
                targetThread.threadId());
    }

    public static void onDeferredTaskEnd() {
        if (!ENABLED || !isSamplingCurrentThread()) {
            return;
        }
        endNanos = System.nanoTime();
        completionReason = "after_runnable_owner_clear";
        ACTIVE.set(false);
    }

    private static void sampleLoop() {
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
                StackTraceElement leaf = selectLeaf(stack);
                if (leaf != null) {
                    leafCounts.merge(frameKey(leaf), 1, Integer::sum);
                }
                StackTraceElement xaeroFrame = selectXaeroFrame(stack);
                if (xaeroFrame != null) {
                    xaeroCounts.merge(frameKey(xaeroFrame), 1, Integer::sum);
                }
            }

            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (ACTIVE.getAndSet(false)) {
                    endNanos = System.nanoTime();
                    completionReason = "sampler_interrupted";
                }
                break;
            }
        }

        if (ACTIVE.getAndSet(false)) {
            endNanos = System.nanoTime();
            completionReason = "timeout";
        }
        if (endNanos == 0L) {
            endNanos = System.nanoTime();
        }

        FINISHED.set(true);
        long wallMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, endNanos - startNanos));
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason={} boundary=fml_active_container owner={} thread={} wall_ms={} samples={} top_leaf={} top_xaero={}",
                completionReason == null ? "unknown" : completionReason,
                ownerId == null ? "unknown" : ownerId,
                targetThread == null ? "unknown" : targetThread.getName(),
                wallMs,
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
        if (startNanos != 0L || endNanos != 0L) {
            return;
        }
        FINISHED.set(true);
        LOGGER.info(
                "BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=title_without_observation boundary=fml_active_container samples=0");
    }
}
