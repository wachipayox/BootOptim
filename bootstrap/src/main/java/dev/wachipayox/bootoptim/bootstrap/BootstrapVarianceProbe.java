package dev.wachipayox.bootoptim.bootstrap;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only copy of the low-noise variance snapshotter for the ModLauncher SERVICE layer. */
final class BootstrapVarianceProbe {
    static final String PROPERTY = "boot_optim.profileStartupVariance";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean()
            instanceof OperatingSystemMXBean bean ? bean : null;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final long JVM_START_EPOCH_MS = ManagementFactory.getRuntimeMXBean().getStartTime();

    private BootstrapVarianceProbe() {}

    static boolean enabled() {
        return ENABLED;
    }

    static Stamp start(String phase) {
        if (!ENABLED) {
            return null;
        }
        Snapshot snapshot = snapshot();
        long scope = SEQUENCE.incrementAndGet();
        log(scope, scope, "start", phase, snapshot, null);
        return new Stamp(scope, snapshot);
    }

    static void point(String phase) {
        if (ENABLED) {
            long seq = SEQUENCE.incrementAndGet();
            log(seq, 0L, "point", phase, snapshot(), null);
        }
    }

    static void finish(String phase, Stamp started) {
        if (ENABLED && started != null) {
            long seq = SEQUENCE.incrementAndGet();
            log(seq, started.scope, "end", phase, snapshot(), started.snapshot);
        }
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
        return new Snapshot(
                System.nanoTime(),
                System.currentTimeMillis(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                OS == null ? -1L : OS.getProcessCpuTime(),
                threadCpu,
                Thread.currentThread().threadId(),
                gcCountKnown ? gcCount : -1L,
                gcTimeKnown ? gcTimeMs : -1L,
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                OS == null ? -1L : OS.getFreeMemorySize());
    }

    private static void log(long seq, long scope, String event, String phase, Snapshot now, Snapshot before) {
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
        System.out.printf(
                Locale.ROOT,
                "BOOTOPTIM_VARIANCE seq=%d scope=%d event=%s phase=%s subject=- mono_ns=%d wall_epoch_ms=%d jvm_start_epoch_ms=%d uptime_ms=%d process_cpu_ms=%s thread_cpu_ms=%s thread_id=%d gc_count=%d gc_time_ms=%d heap_used_mib=%s heap_committed_mib=%s heap_max_mib=%s available_memory_mib=%s elapsed_ms=%s process_cpu_delta_ms=%s owner_thread_cpu_delta_ms=%s gc_count_delta=%d gc_time_delta_ms=%d heap_used_delta_mib=%s available_memory_delta_mib=%s%n",
                seq,
                scope,
                event,
                phase,
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
        return after < 0L || before < 0L || after < before ? -1L : after - before;
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

    record Stamp(long scope, Snapshot snapshot) {}

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
