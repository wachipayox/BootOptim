package dev.wachipayox.bootoptim.compat.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * Experimental cooperative replacement for FancyMenu's empty-body preload waits.
 *
 * <p>This class has no compile-time FancyMenu dependency. The caller must fall back to the wrapped
 * original invocation when {@link #tryWait(Object, long, Family)} returns {@code false}.</p>
 */
public final class FancyMenuCooperativeWait {
    private static final String RESOURCE_CLASS = "de.keksuccino.fancymenu.util.resource.Resource";
    public static final long PARK_QUANTUM_NANOS = 100_000L;
    static final long DEADLINE_SPIN_MILLIS = 1L;

    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    private static volatile Access access;
    private static volatile Metrics activeMetrics;

    private FancyMenuCooperativeWait() {}

    public enum Family {
        ORDINARY,
        SLIDESHOW,
        PANORAMA
    }

    public static void beginPreload() {
        Metrics metrics = new Metrics(Thread.currentThread());
        if (THREAD_CPU.isCurrentThreadCpuTimeSupported()) {
            try {
                metrics.cpuWasEnabled = THREAD_CPU.isThreadCpuTimeEnabled();
                if (!metrics.cpuWasEnabled) {
                    THREAD_CPU.setThreadCpuTimeEnabled(true);
                    metrics.cpuEnabledByUs = true;
                }
                metrics.cpuAvailable = THREAD_CPU.isThreadCpuTimeEnabled();
            } catch (RuntimeException ignored) {
                metrics.cpuAvailable = false;
            }
        }
        metrics.preloadWallStartNanos = System.nanoTime();
        metrics.preloadCpuStartNanos = readCpu(metrics);
        activeMetrics = metrics;
    }

    public static Snapshot finishPreload() {
        Metrics metrics = activeMetrics;
        if (metrics == null || metrics.owner != Thread.currentThread()) {
            return null;
        }

        long preloadWallNanos = Math.max(0L, System.nanoTime() - metrics.preloadWallStartNanos);
        long preloadCpuEnd = readCpu(metrics);
        long preloadCpuNanos = cpuDelta(metrics.preloadCpuStartNanos, preloadCpuEnd);
        boolean cpuAvailable = metrics.cpuAvailable && preloadCpuNanos >= 0L;
        activeMetrics = null;

        if (metrics.cpuEnabledByUs) {
            try {
                THREAD_CPU.setThreadCpuTimeEnabled(false);
            } catch (RuntimeException ignored) {
                // Candidate-only measurement must never alter FancyMenu's completion/error path.
            }
        }

        FamilyMetrics ordinary = metrics.families.get(Family.ORDINARY);
        FamilyMetrics slideshow = metrics.families.get(Family.SLIDESHOW);
        FamilyMetrics panorama = metrics.families.get(Family.PANORAMA);
        return new Snapshot(
                cpuAvailable,
                preloadWallNanos,
                cpuAvailable ? preloadCpuNanos : -1L,
                ordinary.calls,
                ordinary.cooperativeCalls,
                ordinary.wallNanos,
                ordinary.cpuNanos,
                slideshow.calls,
                slideshow.cooperativeCalls,
                slideshow.wallNanos,
                slideshow.cpuNanos,
                panorama.calls,
                panorama.cooperativeCalls,
                panorama.wallNanos,
                panorama.cpuNanos,
                metrics.parkCalls,
                metrics.deadlineSpins,
                metrics.interruptFallbacks,
                metrics.virtualFallbacks,
                metrics.parkFailures,
                metrics.timerFallbacks,
                metrics.accessFailures,
                metrics.stockFallbacks);
    }

    /**
     * Returns {@code true} only when the original wait was fully replaced by this helper.
     * A {@code false} result means the wrapper must invoke FancyMenu's original operation once.
     */
    public static boolean tryWait(Object resource, long timeoutMillis, Family family) {
        Metrics metrics = ownedMetrics();
        FamilyMetrics familyMetrics = metrics == null ? null : metrics.families.get(family);
        if (familyMetrics != null) {
            familyMetrics.calls++;
        }

        final Access resolved;
        try {
            resolved = resolveAccess(resource);
        } catch (AccessFailure failure) {
            if (metrics != null) {
                metrics.accessFailures++;
                metrics.stockFallbacks++;
            }
            return false;
        }

        long wallStart = System.nanoTime();
        long cpuStart = readCpu(metrics);
        WaitOutcome outcome = waitFor(
                () -> resolved.isCompleted(resource),
                () -> resolved.isFailed(resource),
                timeoutMillis,
                SystemWaitSupport.INSTANCE);
        long wallNanos = Math.max(0L, System.nanoTime() - wallStart);
        long cpuEnd = readCpu(metrics);
        long cpuNanos = cpuDelta(cpuStart, cpuEnd);

        if (familyMetrics != null) {
            familyMetrics.cooperativeCalls++;
            familyMetrics.wallNanos += wallNanos;
            if (cpuNanos >= 0L) {
                familyMetrics.cpuNanos += cpuNanos;
            }
        }
        if (metrics != null) {
            metrics.parkCalls += outcome.parkCalls();
            metrics.deadlineSpins += outcome.deadlineSpin() ? 1L : 0L;
            metrics.interruptFallbacks += outcome.interruptFallback() ? 1L : 0L;
            metrics.virtualFallbacks += outcome.virtualFallback() ? 1L : 0L;
            metrics.parkFailures += outcome.parkFailure() ? 1L : 0L;
            metrics.timerFallbacks += outcome.timerFallback() ? 1L : 0L;
        }
        return true;
    }

    static WaitOutcome waitFor(
            BooleanSupplier completed,
            BooleanSupplier failed,
            long timeoutMillis,
            WaitSupport support) {
        long startMillis = support.currentTimeMillis();
        long deadlineMillis = startMillis + timeoutMillis;
        long parkCalls = 0L;
        boolean deadlineSpin = false;
        boolean interruptFallback = false;
        boolean virtualFallback = false;
        boolean parkFailure = false;
        boolean timerFallback = false;
        boolean spin = false;

        while (true) {
            // Keep the exact FancyMenu predicate order: completion -> failure -> deadline.
            if (completed.getAsBoolean()) {
                return new WaitOutcome(
                        parkCalls,
                        deadlineSpin,
                        interruptFallback,
                        virtualFallback,
                        parkFailure,
                        timerFallback);
            }
            if (failed.getAsBoolean()) {
                return new WaitOutcome(
                        parkCalls,
                        deadlineSpin,
                        interruptFallback,
                        virtualFallback,
                        parkFailure,
                        timerFallback);
            }

            long nowMillis = support.currentTimeMillis();
            if (!(deadlineMillis > nowMillis)) {
                return new WaitOutcome(
                        parkCalls,
                        deadlineSpin,
                        interruptFallback,
                        virtualFallback,
                        parkFailure,
                        timerFallback);
            }

            // Stock does not react to interruption. Never clear the bit. An interrupted park would
            // return immediately forever, so preserve stock-like bounded spinning to the same deadline.
            if (!spin && support.isCurrentThreadInterrupted()) {
                interruptFallback = true;
                spin = true;
            }
            if (!spin && support.isCurrentThreadVirtual()) {
                virtualFallback = true;
                spin = true;
            }
            if (spin) {
                continue;
            }

            long remainingMillis = deadlineMillis - nowMillis;
            if (remainingMillis <= 0L) {
                // Defensive wall-clock arithmetic anomaly: keep the same absolute deadline and spin.
                timerFallback = true;
                spin = true;
                continue;
            }

            // Avoid scheduler overshoot at the final millisecond; stock's deadline is millisecond based.
            if (remainingMillis <= DEADLINE_SPIN_MILLIS) {
                deadlineSpin = true;
                spin = true;
                continue;
            }

            long remainingNanos =
                    remainingMillis > Long.MAX_VALUE / 1_000_000L
                            ? Long.MAX_VALUE
                            : remainingMillis * 1_000_000L;
            long parkNanos = Math.min(PARK_QUANTUM_NANOS, remainingNanos);
            try {
                support.parkNanos(parkNanos);
                parkCalls++;
            } catch (RuntimeException | LinkageError failure) {
                parkFailure = true;
                spin = true;
            }
        }
    }

    private static Metrics ownedMetrics() {
        Metrics metrics = activeMetrics;
        return metrics != null && metrics.owner == Thread.currentThread() ? metrics : null;
    }

    private static long readCpu(Metrics metrics) {
        if (metrics == null || !metrics.cpuAvailable) {
            return -1L;
        }
        try {
            long value = THREAD_CPU.getCurrentThreadCpuTime();
            if (value < 0L) {
                metrics.cpuAvailable = false;
            }
            return value;
        } catch (RuntimeException ignored) {
            metrics.cpuAvailable = false;
            return -1L;
        }
    }

    private static long cpuDelta(long start, long end) {
        return start >= 0L && end >= start ? end - start : -1L;
    }

    private static Access resolveAccess(Object resource) throws AccessFailure {
        if (resource == null) {
            throw new AccessFailure("null resource");
        }

        Access current = access;
        if (current != null && current.resourceType.isInstance(resource)) {
            return current;
        }

        synchronized (FancyMenuCooperativeWait.class) {
            current = access;
            if (current != null && current.resourceType.isInstance(resource)) {
                return current;
            }
            try {
                ClassLoader loader = resource.getClass().getClassLoader();
                Class<?> resourceType = Class.forName(RESOURCE_CLASS, false, loader);
                if (!resourceType.isInstance(resource)) {
                    throw new AccessFailure("receiver is not FancyMenu Resource");
                }
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodHandle completed = lookup.findVirtual(
                                resourceType,
                                "isLoadingCompleted",
                                MethodType.methodType(boolean.class))
                        .asType(MethodType.methodType(boolean.class, Object.class));
                MethodHandle failed = lookup.findVirtual(
                                resourceType,
                                "isLoadingFailed",
                                MethodType.methodType(boolean.class))
                        .asType(MethodType.methodType(boolean.class, Object.class));
                current = new Access(resourceType, completed, failed);
                access = current;
                return current;
            } catch (AccessFailure failure) {
                throw failure;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                throw new AccessFailure("FancyMenu Resource access unavailable", failure);
            }
        }
    }

    private record Access(Class<?> resourceType, MethodHandle completed, MethodHandle failed) {
        boolean isCompleted(Object resource) {
            try {
                return (boolean) completed.invokeExact(resource);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("FancyMenu completion predicate failed", failure);
            }
        }

        boolean isFailed(Object resource) {
            try {
                return (boolean) failed.invokeExact(resource);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("FancyMenu failure predicate failed", failure);
            }
        }
    }

    private static final class AccessFailure extends Exception {
        AccessFailure(String message) {
            super(message);
        }

        AccessFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface WaitSupport {
        long currentTimeMillis();

        boolean isCurrentThreadInterrupted();

        boolean isCurrentThreadVirtual();

        void parkNanos(long nanos);
    }

    private enum SystemWaitSupport implements WaitSupport {
        INSTANCE;

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public boolean isCurrentThreadInterrupted() {
            return Thread.currentThread().isInterrupted();
        }

        @Override
        public boolean isCurrentThreadVirtual() {
            return Thread.currentThread().isVirtual();
        }

        @Override
        public void parkNanos(long nanos) {
            LockSupport.parkNanos(nanos);
        }
    }

    record WaitOutcome(
            long parkCalls,
            boolean deadlineSpin,
            boolean interruptFallback,
            boolean virtualFallback,
            boolean parkFailure,
            boolean timerFallback) {}

    private static final class Metrics {
        final Thread owner;
        final EnumMap<Family, FamilyMetrics> families = new EnumMap<>(Family.class);
        boolean cpuWasEnabled;
        boolean cpuEnabledByUs;
        boolean cpuAvailable;
        long preloadWallStartNanos;
        long preloadCpuStartNanos;
        long parkCalls;
        long deadlineSpins;
        long interruptFallbacks;
        long virtualFallbacks;
        long parkFailures;
        long timerFallbacks;
        long accessFailures;
        long stockFallbacks;

        Metrics(Thread owner) {
            this.owner = owner;
            for (Family family : Family.values()) {
                families.put(family, new FamilyMetrics());
            }
        }
    }

    private static final class FamilyMetrics {
        long calls;
        long cooperativeCalls;
        long wallNanos;
        long cpuNanos;
    }

    public record Snapshot(
            boolean cpuAvailable,
            long preloadWallNanos,
            long preloadCpuNanos,
            long ordinaryCalls,
            long ordinaryCooperativeCalls,
            long ordinaryWallNanos,
            long ordinaryCpuNanos,
            long slideshowCalls,
            long slideshowCooperativeCalls,
            long slideshowWallNanos,
            long slideshowCpuNanos,
            long panoramaCalls,
            long panoramaCooperativeCalls,
            long panoramaWallNanos,
            long panoramaCpuNanos,
            long parkCalls,
            long deadlineSpins,
            long interruptFallbacks,
            long virtualFallbacks,
            long parkFailures,
            long timerFallbacks,
            long accessFailures,
            long stockFallbacks) {
        public long waitCalls() {
            return ordinaryCalls + slideshowCalls + panoramaCalls;
        }

        public long cooperativeCalls() {
            return ordinaryCooperativeCalls + slideshowCooperativeCalls + panoramaCooperativeCalls;
        }
    }
}
