package dev.wachipayox.bootoptim.optimization.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.BufferedReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in experiment: bound only ModelManager resource-open concurrency. */
public final class ModelInputIoLane {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelInputIoLane");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.experimentModelInputIoLane");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final AtomicReference<Trace> ACTIVE = new AtomicReference<>();
    private static final ThreadLocal<Boolean> INSIDE_OPEN = ThreadLocal.withInitial(() -> false);

    private ModelInputIoLane() {}

    public static Trace beginReload() {
        if (!ENABLED) return null;
        Trace trace = new Trace(NEXT_ID.incrementAndGet());
        if (!ACTIVE.compareAndSet(null, trace)) {
            LOGGER.warn("BOOTOPTIM_MODEL_IO_LANE id={} status=overlap_fallback", trace.id);
            return null;
        }
        return trace;
    }

    public static void observeReload(Trace trace, CompletableFuture<?> future) {
        if (trace == null) return;
        if (future == null) {
            ACTIVE.compareAndSet(trace, null);
            LOGGER.warn("BOOTOPTIM_MODEL_IO_LANE id={} status=missing_future", trace.id);
            return;
        }
        future.whenComplete((value, error) -> {
            ACTIVE.compareAndSet(trace, null);
            LOGGER.info("BOOTOPTIM_MODEL_IO_LANE id={} status={} attempted={} bounded={} fallback={} wait_ms_sum={} open_ms_sum={}",
                    trace.id, error == null ? "success" : "failure", trace.attempted.sum(),
                    trace.bounded.sum(), trace.fallback.sum(), trace.waitNanos.sum() / 1_000_000,
                    trace.openNanos.sum() / 1_000_000);
        });
    }

    public static BufferedReader open(Resource resource, Operation<BufferedReader> original) {
        Trace trace = ACTIVE.get();
        if (trace == null || trace.tripped || INSIDE_OPEN.get()) return original.call(resource);
        trace.attempted.increment();
        long waitStart = System.nanoTime();
        boolean acquired;
        try {
            acquired = trace.permits.tryAcquire(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        trace.waitNanos.add(Math.max(0, System.nanoTime() - waitStart));
        if (!acquired) {
            trace.tripped = true;
            trace.fallback.increment();
            return original.call(resource);
        }
        trace.bounded.increment();
        INSIDE_OPEN.set(true);
        long openStart = System.nanoTime();
        try {
            return original.call(resource);
        } finally {
            trace.openNanos.add(Math.max(0, System.nanoTime() - openStart));
            INSIDE_OPEN.remove();
            trace.permits.release();
        }
    }

    public static final class Trace {
        private final int id;
        private final Semaphore permits = new Semaphore(2, true);
        private final LongAdder attempted = new LongAdder();
        private final LongAdder bounded = new LongAdder();
        private final LongAdder fallback = new LongAdder();
        private final LongAdder waitNanos = new LongAdder();
        private final LongAdder openNanos = new LongAdder();
        private volatile boolean tripped;

        private Trace(int id) { this.id = id; }
    }
}
