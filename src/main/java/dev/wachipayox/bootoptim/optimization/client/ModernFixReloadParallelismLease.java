package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Experimental one-shot lease over ModernFix's existing dedicated resource-reload ForkJoinPool.
 *
 * <p>No executor, task or future is replaced. The only runtime mutation is a temporary reduction
 * of the pool's target parallelism from the exact-pack stock value 3 to 2, followed by restoration
 * of the exact previous target when the startup reload terminates.</p>
 */
public final class ModernFixReloadParallelismLease {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXPECTED_MODERNFIX_VERSION = "5.27.14+mc1.21.1";
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentModernFixReloadLease", "false"));
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private ModernFixReloadParallelismLease() {}

    public static Lease tryAcquire(Executor executor) {
        if (!ENABLED) {
            return null;
        }
        if (!ATTEMPTED.compareAndSet(false, true)) {
            reportSkip("reentry");
            return null;
        }

        String modernFixVersion;
        try {
            modernFixVersion = ModList.get()
                    .getModContainerById("modernfix")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            reportSkip("modernfix_probe_failed_" + failure.getClass().getSimpleName());
            return null;
        }
        if (!EXPECTED_MODERNFIX_VERSION.equals(modernFixVersion)) {
            reportSkip(modernFixVersion == null ? "modernfix_absent" : "modernfix_version_" + modernFixVersion);
            return null;
        }
        if (!(executor instanceof ForkJoinPool pool)) {
            reportSkip("prepare_executor_not_forkjoinpool");
            return null;
        }
        if (pool == ForkJoinPool.commonPool()) {
            reportSkip("prepare_executor_is_common_pool");
            return null;
        }
        if (pool.isShutdown() || pool.isTerminating() || pool.isTerminated()) {
            reportSkip("prepare_executor_not_live");
            return null;
        }

        int visibleProcessors = Runtime.getRuntime().availableProcessors();
        int previousParallelism = pool.getParallelism();
        if (visibleProcessors > 4) {
            reportSkip("visible_processors_" + visibleProcessors);
            return null;
        }
        if (previousParallelism != 3) {
            reportSkip("unexpected_parallelism_" + previousParallelism);
            return null;
        }

        int targetParallelism = 2;
        try {
            int returnedPrevious = pool.setParallelism(targetParallelism);
            int observed = pool.getParallelism();
            if (returnedPrevious != previousParallelism || observed != targetParallelism) {
                try {
                    pool.setParallelism(previousParallelism);
                } catch (RuntimeException ignored) {
                    // The final marker below records failure; never substitute another executor.
                }
                reportSkip("set_parallelism_mismatch_prev_" + returnedPrevious + "_observed_" + observed);
                return null;
            }
        } catch (RuntimeException failure) {
            reportSkip("set_parallelism_failed_" + failure.getClass().getSimpleName());
            return null;
        }

        return new Lease(pool, visibleProcessors, previousParallelism, targetParallelism);
    }

    private static void reportSkip(String reason) {
        LOGGER.info("BOOTOPTIM_MODERNFIX_RELOAD_LEASE status=skipped reason={}", reason);
    }

    public static final class Lease {
        private final ForkJoinPool pool;
        private final int visibleProcessors;
        private final int previousParallelism;
        private final int targetParallelism;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(
                ForkJoinPool pool,
                int visibleProcessors,
                int previousParallelism,
                int targetParallelism) {
            this.pool = pool;
            this.visibleProcessors = visibleProcessors;
            this.previousParallelism = previousParallelism;
            this.targetParallelism = targetParallelism;
        }

        public void close(Throwable completionFailure, boolean cancelled) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            int beforeRestore = pool.getParallelism();
            boolean restored = false;
            String restoreFailure = "none";
            try {
                pool.setParallelism(previousParallelism);
                restored = pool.getParallelism() == previousParallelism;
            } catch (RuntimeException failure) {
                restoreFailure = failure.getClass().getSimpleName();
            }

            String outcome = cancelled ? "cancelled" : completionFailure == null ? "success" : "failure";
            LOGGER.info(
                    "BOOTOPTIM_MODERNFIX_RELOAD_LEASE status={} applied=true restored={} outcome={} visible_processors={} previous_parallelism={} target_parallelism={} before_restore={} after_restore={} restore_failure={}",
                    restored ? "ok" : "restore_failed",
                    restored,
                    outcome,
                    visibleProcessors,
                    previousParallelism,
                    targetParallelism,
                    beforeRestore,
                    pool.getParallelism(),
                    restoreFailure);
        }
    }
}
