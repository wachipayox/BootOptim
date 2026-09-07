package dev.wachipayox.bootoptim.profiling;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only, low-cardinality JVM/process snapshots around startup boundaries.
 *
 * <p>The probe is disabled unless {@code -Dboot_optim.profileStartupVariance=true} is supplied.
 * It samples only when an explicit startup boundary calls it: no polling, stack walking, JFR, sleeps,
 * resource wrapping, or hot-path per-call instrumentation.</p>
 */
public final class VarianceProbe {
    public static final String PROPERTY = "boot_optim.profileStartupVariance";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/Variance");
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean()
            instanceof OperatingSystemMXBean bean ? bean : null;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final long JVM_START_EPOCH_MS = ManagementFactory.getRuntimeMXBean().getStartTime();

    private VarianceProbe() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static Stamp start(String phase) {
        return start(phase, "-");
    }

    public static Stamp start(String phase, String subject) {
        if (!ENABLED) {
            return null;
        }
        Snapshot snapshot = snapshot();
        long scope = SEQUENCE.incrementAndGet();
        log(scope, scope, "start", phase, subject, snapshot, null);
        return new Stamp(scope, snapshot);
    }

    public static void point(String phase) {
        point(phase, "-");
    }

    public static void point(String phase, String subject) {
        if (!ENABLED) {
            return;
        }
        long seq = SEQUENCE.incrementAndGet();
        log(seq, 0L, "point", phase, subject, snapshot(), null);
    }

    public static void finish(String phase, Stamp started) {
        finish(phase, "-", started);
    }

    public static void finish(String phase, String subject, Stamp started) {
        if (!ENABLED || started == null) {
            return;
        }
        long seq = SEQUENCE.incrementAndGet();
        log(seq, started.scope, "end", phase, subject, snapshot(), started.snapshot);
    }

    private static Snapshot snapshot() {
        long threadCpu = -1L;
        if (THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()) {
            threadCpu = THREADS.getCurrentThreadCpuTime();
        }
        long gcCount = 0L;
        long gcTimeMs = 0L;
        boolean gcCountKnown = false;
        boolean gcTimeKnown = false;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count >= 0L) {
                gcCount += count;
                gcCountKnown = true;
            }
            if (time >= 0L) {
                gcTimeMs += time;
                gcTimeKnown = true;
            }
        }
        var heap = MEMORY.getHeapMemoryUsage();
        long threadId = Thread.currentThread().threadId();
        return new Snapshot(
                System.nanoTime(),
                System.currentTimeMillis(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                processCpuNanos(),
                threadCpu,
                threadId,
                gcCountKnown ? gcCount : -1L,
                gcTimeKnown ? gcTimeMs : -1L,
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                availableMemoryBytes());
    }

    private static long processCpuNanos() {
        return OS == null ? -1L : OS.getProcessCpuTime();
    }

    private static long availableMemoryBytes() {
        return OS == null ? -1L : OS.getFreeMemorySize();
    }

    private static void log(
            long seq,
            long scope,
            String event,
            String phase,
            String subject,
            Snapshot now,
            Snapshot before) {
        long elapsed = delta(now.monoNanos, before == null ? -1L : before.monoNanos);
        long processCpuDelta = delta(now.processCpuNanos, before == null ? -1L : before.processCpuNanos);
        long ownerThreadCpuDelta = before != null && before.threadId == now.threadId
                ? delta(now.threadCpuNanos, before.threadCpuNanos)
                : -1L;
        long gcCountDelta = delta(now.gcCount, before == null ? -1L : before.gcCount);
        long gcTimeDelta = delta(now.gcTimeMs, before == null ? -1L : before.gcTimeMs);
        long heapDelta = before == null ? Long.MIN_VALUE : now.heapUsedBytes - before.heapUsedBytes;
        long availableDelta = before == null || now.availableMemoryBytes < 0L || before.availableMemoryBytes < 0L
                ? Long.MIN_VALUE
                : now.availableMemoryBytes - before.availableMemoryBytes;

        LOGGER.info(
                "BOOTOPTIM_VARIANCE seq={} scope={} event={} phase={} subject={} mono_ns={} wall_epoch_ms={} jvm_start_epoch_ms={} uptime_ms={} process_cpu_ms={} thread_cpu_ms={} thread_id={} gc_count={} gc_time_ms={} heap_used_mib={} heap_committed_mib={} heap_max_mib={} available_memory_mib={} elapsed_ms={} process_cpu_delta_ms={} owner_thread_cpu_delta_ms={} gc_count_delta={} gc_time_delta_ms={} heap_used_delta_mib={} available_memory_delta_mib={}",
                seq,
                scope,
                token(event),
                token(phase),
                token(subject),
                now.monoNanos,
                now.wallEpochMs,
                JVM_START_EPOCH_MS,
                now.uptimeMs,
                nanosToMs(now.processCpuNanos),
                nanosToMs(now.threadCpuNanos),
                now.threadId,
                now.gcCount,
                now.gcTimeMs,
                bytesToMiB(now.heapUsedBytes),
                bytesToMiB(now.heapCommittedBytes),
                bytesToMiB(now.heapMaxBytes),
                bytesToMiB(now.availableMemoryBytes),
                nanosToMs(elapsed),
                nanosToMs(processCpuDelta),
                nanosToMs(ownerThreadCpuDelta),
                gcCountDelta,
                gcTimeDelta,
                bytesDeltaToMiB(heapDelta),
                bytesDeltaToMiB(availableDelta));
    }

    private static long delta(long after, long before) {
        if (after < 0L || before < 0L || after < before) {
            return -1L;
        }
        return after - before;
    }

    private static String nanosToMs(long nanos) {
        return nanos < 0L ? "-1" : format(nanos / 1_000_000.0D);
    }

    private static String bytesToMiB(long bytes) {
        return bytes < 0L ? "-1" : format(bytes / (1024.0D * 1024.0D));
    }

    private static String bytesDeltaToMiB(long bytes) {
        return bytes == Long.MIN_VALUE ? "-1" : format(bytes / (1024.0D * 1024.0D));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replaceAll("[^A-Za-z0-9_.$:/#-]", "_");
    }

    public record Stamp(long scope, Snapshot snapshot) {}

    private record Snapshot(
            long monoNanos,
            long wallEpochMs,
            long uptimeMs,
            long processCpuNanos,
            long threadCpuNanos,
            long threadId,
            long gcCount,
            long gcTimeMs,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long availableMemoryBytes) {}
}
