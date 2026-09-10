package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Low-overhead timing for FML discovery phases, active only during startup profiling. */
final class DiscoveryProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileStartup")
            || Boolean.getBoolean("boot_optim.benchmark.exitOnTitle");
    private static final boolean ARTIFACT_DETAIL = Boolean.getBoolean("boot_optim.profileDiscoveryArtifacts");
    private static final AtomicLong ROOT_START = new AtomicLong();
    private static final AtomicLong DEPENDENCY_START = new AtomicLong();
    private static final AtomicLong ROOT_TASK = new AtomicLong();
    private static final AtomicLong DEPENDENCY_TASK = new AtomicLong();
    private static final AtomicLong ROOT_ACTIVE_TASK = new AtomicLong();
    private static final AtomicLong DEPENDENCY_ACTIVE_TASK = new AtomicLong();
    private static final AtomicLong ROOT_OWNER_THREAD = new AtomicLong(-1L);
    private static final AtomicLong DEPENDENCY_OWNER_THREAD = new AtomicLong(-1L);
    private static final ThreadLocal<Long> PREVIOUS_ARTIFACT_TASK = ThreadLocal.withInitial(() -> 0L);
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();

    private DiscoveryProfiler() {}

    static void beginRoot() {
        begin("root_mod_discovery", ROOT_START, ROOT_TASK, ROOT_ACTIVE_TASK, ROOT_OWNER_THREAD, null);
        PREVIOUS_ARTIFACT_TASK.set(0L);
        DiscoveryJfrProfiler.begin();
    }

    static void endRoot() {
        end("root_mod_discovery", ROOT_START, ROOT_TASK, ROOT_ACTIVE_TASK);
        PREVIOUS_ARTIFACT_TASK.remove();
    }

    static void beginDependencies() {
        long rootTask = ROOT_TASK.get();
        long[] dependencies = rootTask == 0L ? null : new long[] { rootTask };
        begin("dependency_discovery", DEPENDENCY_START, DEPENDENCY_TASK, DEPENDENCY_ACTIVE_TASK,
                DEPENDENCY_OWNER_THREAD, dependencies);
        PREVIOUS_ARTIFACT_TASK.set(0L);
    }

    static void endDependencies() {
        end("dependency_discovery", DEPENDENCY_START, DEPENDENCY_TASK, DEPENDENCY_ACTIVE_TASK);
        PREVIOUS_ARTIFACT_TASK.remove();
        DiscoveryJfrProfiler.end();
    }

    /** Causal predecessor for the first post-discovery FML task; zero means no trace task was emitted. */
    static long dependencyTaskId() {
        return DEPENDENCY_TASK.get();
    }

    static boolean artifactDetailEnabled() {
        return ARTIFACT_DETAIL && TRACE.isDetailed();
    }

    /** Structural parent only when the reader callback is lexically inside the measured phase on this thread. */
    static long currentTaskIdForCurrentThread() {
        long threadId = Thread.currentThread().threadId();
        long dependency = DEPENDENCY_ACTIVE_TASK.get();
        if (dependency != 0L && DEPENDENCY_OWNER_THREAD.get() == threadId) return dependency;
        long root = ROOT_ACTIVE_TASK.get();
        if (root != 0L && ROOT_OWNER_THREAD.get() == threadId) return root;
        return 0L;
    }

    static long previousArtifactTaskForCurrentThread() {
        return PREVIOUS_ARTIFACT_TASK.get();
    }

    static void noteArtifactTaskForCurrentThread(long taskId) {
        if (taskId != 0L) PREVIOUS_ARTIFACT_TASK.set(taskId);
    }

    private static void begin(String phase, AtomicLong holder, AtomicLong taskHolder, AtomicLong activeTask,
            AtomicLong ownerThread, long[] dependencies) {
        if (!ENABLED && !TRACE.isEnabled()) {
            return;
        }
        long start = System.nanoTime();
        if (holder.compareAndSet(0L, start)) {
            long task = TRACE.beginTask(phase, 0L, dependencies, null, null, -1L);
            taskHolder.compareAndSet(0L, task);
            activeTask.set(task);
            ownerThread.set(Thread.currentThread().threadId());
            System.out.printf("BOOTOPTIM_STARTUP phase=%s_start uptime_ms=%d%n",
                    phase, ManagementFactory.getRuntimeMXBean().getUptime());
        }
    }

    private static void end(String phase, AtomicLong holder, AtomicLong taskHolder, AtomicLong activeTask) {
        if (!ENABLED && !TRACE.isEnabled()) {
            return;
        }
        long start = holder.get();
        if (start == 0L) {
            return;
        }
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        long taskId = taskHolder.get();
        activeTask.set(0L);
        if (taskId != 0L) {
            TRACE.endTask(taskId, phase, -1L, "fml_discovery_end");
        }
        System.out.printf("BOOTOPTIM_STARTUP phase=%s_end uptime_ms=%d elapsed_ms=%.3f%n",
                phase, ManagementFactory.getRuntimeMXBean().getUptime(), elapsedMs);
    }
}
