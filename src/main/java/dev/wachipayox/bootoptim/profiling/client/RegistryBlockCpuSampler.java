package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

/**
 * Diagnostic-only statistical stack sampler for the thread executing the minecraft:block RegisterEvent.
 *
 * <p>The sampler never changes registry/event-bus ordering and never logs per sample. It sleeps on a daemon
 * observer thread, samples only the exact thread captured by the already-validated GameData callsite boundary,
 * and drops any sample whose stack capture overlaps the AFTER boundary. Counts are statistical observations,
 * not recoverable wall time or exact per-method CPU attribution.</p>
 */
public final class RegistryBlockCpuSampler {
    public static final String PROFILE_PROPERTY = "boot_optim.profileRegistryBlockCpu";

    private static final boolean ENABLED = Boolean.getBoolean(PROFILE_PROPERTY);
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final long SAMPLE_INTERVAL_NANOS = 15_000_000L;
    private static final int MAX_SAMPLES = 768;
    private static final int MAX_METHODS = 384;
    private static final int MAX_STACKS = 256;
    private static final int MAX_STACK_FRAMES = 24;
    private static final int TOP_METHODS = 24;
    private static final int TOP_STACKS = 12;

    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();

    private RegistryBlockCpuSampler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void begin(RegisterEvent event) {
        if (!ENABLED || !isBlockRegistry(event)) {
            return;
        }

        try {
            Thread target = Thread.currentThread();
            long startedNanos = System.nanoTime();
            long startedCpuNanos = threadCpuNanos(target.threadId());
            Session session = new Session(target, startedNanos, startedCpuNanos);
            if (!ACTIVE.compareAndSet(null, session)) {
                return;
            }

            Thread sampler = Thread.ofPlatform()
                    .daemon(true)
                    .name("bootoptim-registry-block-cpu-sampler")
                    .unstarted(() -> sampleLoop(session));
            session.samplerThread = sampler;
            sampler.start();
        } catch (Throwable throwable) {
            ACTIVE.set(null);
            logger().info(
                    "BOOTOPTIM_REGISTRY_BLOCK_CPU status=instrumentation_failure stage=begin error={}",
                    throwable.toString());
        }
    }

    public static void end(RegisterEvent event) {
        if (!ENABLED || !isBlockRegistry(event)) {
            return;
        }

        try {
            Session session = ACTIVE.get();
            if (session == null || session.target != Thread.currentThread()) {
                return;
            }

            // Record the exact AFTER boundary before stopping the observer. A sample that straddles this timestamp
            // is discarded by the sampler thread, so post-dispatch callback work is not included.
            session.endedNanos = System.nanoTime();
            session.endedCpuNanos = threadCpuNanos(session.target.threadId());
            session.active.set(false);
            ACTIVE.compareAndSet(session, null);
            Thread sampler = session.samplerThread;
            if (sampler != null) {
                LockSupport.unpark(sampler);
            }
        } catch (Throwable throwable) {
            ACTIVE.set(null);
            logger().info(
                    "BOOTOPTIM_REGISTRY_BLOCK_CPU status=instrumentation_failure stage=end error={}",
                    throwable.toString());
        }
    }

    private static void sampleLoop(Session session) {
        try {
            long previousCpuNanos = session.startedCpuNanos;

            // Deliberately delay the first observation so the BEFORE callback can return and stock dispatch can begin.
            while (session.active.get() && session.sampleCount < MAX_SAMPLES) {
                LockSupport.parkNanos(SAMPLE_INTERVAL_NANOS);
                if (!session.active.get()) {
                    break;
                }

                long captureStartedNanos = System.nanoTime();
                long boundary = session.endedNanos;
                if (captureStartedNanos >= boundary) {
                    break;
                }

                long cpuNanos = threadCpuNanos(session.target.threadId());
                Thread.State state = session.target.getState();
                StackTraceElement[] trace = session.target.getStackTrace();
                long captureEndedNanos = System.nanoTime();

                // Strictly exclude a sample if stack capture crossed the real AFTER callback timestamp.
                if (captureEndedNanos >= session.endedNanos) {
                    continue;
                }

                long cpuProgressNanos = 0L;
                if (cpuNanos >= 0L && previousCpuNanos >= 0L && cpuNanos >= previousCpuNanos) {
                    cpuProgressNanos = cpuNanos - previousCpuNanos;
                }
                if (cpuNanos >= 0L) {
                    previousCpuNanos = cpuNanos;
                }

                recordSample(session, trace, state, cpuProgressNanos);
            }
        } catch (Throwable throwable) {
            session.samplerFailure = throwable.toString();
        } finally {
            publish(session);
        }
    }

    private static void recordSample(
            Session session,
            StackTraceElement[] trace,
            Thread.State state,
            long cpuProgressNanos) {
        session.sampleCount++;
        if (state == Thread.State.RUNNABLE) {
            session.runnableSamples++;
        }
        if (cpuProgressNanos > 0L) {
            session.cpuProgressSamples++;
        }
        session.sampledCpuProgressNanos += cpuProgressNanos;

        StackTraceElement leaf = firstRelevantFrame(trace);
        String method = leaf == null ? "<no-java-frame>" : methodName(leaf);
        addBounded(session.methods, method, cpuProgressNanos, MAX_METHODS, session);

        String stack = stackSignature(trace);
        addBounded(session.stacks, stack, cpuProgressNanos, MAX_STACKS, session);
    }

    private static void addBounded(
            Map<String, SampleTotals> totals,
            String key,
            long cpuProgressNanos,
            int limit,
            Session session) {
        SampleTotals existing = totals.get(key);
        if (existing != null) {
            existing.add(cpuProgressNanos);
            return;
        }
        if (totals.size() >= limit) {
            session.droppedUniqueKeys++;
            return;
        }
        SampleTotals created = new SampleTotals();
        created.add(cpuProgressNanos);
        totals.put(key, created);
    }

    private static StackTraceElement firstRelevantFrame(StackTraceElement[] trace) {
        for (StackTraceElement frame : trace) {
            String className = frame.getClassName();
            if (className.startsWith("dev.wachipayox.bootoptim.mixin.client.GameDataBlockRegistryCpuSamplingMixin")
                    || className.startsWith("dev.wachipayox.bootoptim.profiling.client.RegistryBlockCpuSampler")) {
                continue;
            }
            return frame;
        }
        return trace.length == 0 ? null : trace[0];
    }

    private static String stackSignature(StackTraceElement[] trace) {
        if (trace.length == 0) {
            return "<empty>";
        }
        StringBuilder builder = new StringBuilder(512);
        int appended = 0;
        for (StackTraceElement frame : trace) {
            String className = frame.getClassName();
            if (className.startsWith("dev.wachipayox.bootoptim.mixin.client.GameDataBlockRegistryCpuSamplingMixin")
                    || className.startsWith("dev.wachipayox.bootoptim.profiling.client.RegistryBlockCpuSampler")) {
                continue;
            }
            if (appended > 0) {
                builder.append(" <- ");
            }
            builder.append(methodName(frame));
            appended++;
            if (appended >= MAX_STACK_FRAMES) {
                break;
            }
        }
        return appended == 0 ? "<instrumentation-only>" : builder.toString();
    }

    private static String methodName(StackTraceElement frame) {
        return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private static void publish(Session session) {
        try {
            long endNanos = session.endedNanos;
            if (endNanos == Long.MAX_VALUE) {
                logger().info(
                        "BOOTOPTIM_REGISTRY_BLOCK_CPU status=failed reason=missing_end samples={} thread={} sampler_error={}",
                        session.sampleCount,
                        session.target.getName(),
                        safe(session.samplerFailure));
                return;
            }

            long wallNanos = Math.max(0L, endNanos - session.startedNanos);
            long targetCpuNanos = cpuDelta(session.startedCpuNanos, session.endedCpuNanos);
            long expectedSamples = Math.max(1L, wallNanos / SAMPLE_INTERVAL_NANOS);
            double coveragePercent = Math.min(100.0D, session.sampleCount * 100.0D / expectedSamples);

            logger().info(
                    "BOOTOPTIM_REGISTRY_BLOCK_CPU status=complete registry=minecraft:block window_wall_ms={} target_cpu_ms={} samples={} expected_samples={} coverage_pct={} runnable_samples={} cpu_progress_samples={} sampled_cpu_progress_ms={} interval_ms=15 max_samples={} sample_cap_hit={} unique_methods={} unique_stacks={} dropped_unique_keys={} thread={} sampler_error={}",
                    formatNanos(wallNanos),
                    formatNanos(targetCpuNanos),
                    session.sampleCount,
                    expectedSamples,
                    formatDouble(coveragePercent),
                    session.runnableSamples,
                    session.cpuProgressSamples,
                    formatNanos(session.sampledCpuProgressNanos),
                    MAX_SAMPLES,
                    session.sampleCount >= MAX_SAMPLES,
                    session.methods.size(),
                    session.stacks.size(),
                    session.droppedUniqueKeys,
                    session.target.getName(),
                    safe(session.samplerFailure));

            publishRanking("method", session.methods, session.sampleCount, TOP_METHODS);
            publishRanking("stack", session.stacks, session.sampleCount, TOP_STACKS);

            logger().info(
                    "BOOTOPTIM_REGISTRY_BLOCK_CPU status=interpretation metric=statistical_thread_stack_sampling target_cpu=exact_thread_delta sample_counts=not_wall_or_savings cpu_progress_weight=interval_delta_assigned_to_sample_endpoint pre_post_window_samples=excluded");
        } catch (Throwable throwable) {
            logger().info(
                    "BOOTOPTIM_REGISTRY_BLOCK_CPU status=instrumentation_failure stage=publish error={}",
                    throwable.toString());
        }
    }

    private static void publishRanking(
            String dimension,
            Map<String, SampleTotals> totals,
            int totalSamples,
            int limit) {
        List<Map.Entry<String, SampleTotals>> rows = new ArrayList<>(totals.entrySet());
        rows.sort(Comparator
                .comparingInt((Map.Entry<String, SampleTotals> entry) -> entry.getValue().samples)
                .thenComparingLong(entry -> entry.getValue().cpuProgressNanos)
                .reversed());

        int count = Math.min(limit, rows.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<String, SampleTotals> row = rows.get(i);
            logger().info(
                    "BOOTOPTIM_REGISTRY_BLOCK_CPU dimension={} rank={} samples={} sample_pct={} cpu_progress_weight_ms={} value={}",
                    dimension,
                    i + 1,
                    row.getValue().samples,
                    formatDouble(totalSamples > 0 ? row.getValue().samples * 100.0D / totalSamples : 0.0D),
                    formatNanos(row.getValue().cpuProgressNanos),
                    row.getKey());
        }
    }

    private static boolean isBlockRegistry(RegisterEvent event) {
        try {
            return Registries.BLOCK.equals(event.getRegistryKey());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long threadCpuNanos(long threadId) {
        try {
            if (!THREAD_MX_BEAN.isThreadCpuTimeSupported() || !THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                return -1L;
            }
            return THREAD_MX_BEAN.getThreadCpuTime(threadId);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long cpuDelta(long start, long end) {
        return start >= 0L && end >= start ? end - start : -1L;
    }

    private static String formatNanos(long nanos) {
        if (nanos < 0L) {
            return "unavailable";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String safe(String value) {
        return value == null ? "none" : value.replace(' ', '_');
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }

    private static final class Session {
        private final Thread target;
        private final long startedNanos;
        private final long startedCpuNanos;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final Map<String, SampleTotals> methods = new HashMap<>();
        private final Map<String, SampleTotals> stacks = new HashMap<>();

        private volatile long endedNanos = Long.MAX_VALUE;
        private volatile long endedCpuNanos = -1L;
        private volatile Thread samplerThread;
        private volatile String samplerFailure;

        private int sampleCount;
        private int runnableSamples;
        private int cpuProgressSamples;
        private int droppedUniqueKeys;
        private long sampledCpuProgressNanos;

        private Session(Thread target, long startedNanos, long startedCpuNanos) {
            this.target = target;
            this.startedNanos = startedNanos;
            this.startedCpuNanos = startedCpuNanos;
        }
    }

    private static final class SampleTotals {
        private int samples;
        private long cpuProgressNanos;

        private void add(long cpuProgressNanos) {
            samples++;
            this.cpuProgressNanos += cpuProgressNanos;
        }
    }
}
