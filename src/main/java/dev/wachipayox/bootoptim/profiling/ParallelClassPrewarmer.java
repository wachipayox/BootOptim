package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Experimental parallel class prewarm for classes that vanilla {@code Blocks.<clinit>} is known to load.
 *
 * <p>Classes are loaded but deliberately not initialized. This moves only class lookup/transformation/definition
 * work earlier and keeps static initialization and registration ordering unchanged.
 */
public final class ParallelClassPrewarmer {
    private static final String ENABLE_PROPERTY = "boot_optim.classPrewarm";
    private static final String RESOURCE = "/boot_optim/blocks-prewarm-classes.txt";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private ParallelClassPrewarmer() {
    }

    public static void start() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || !STARTED.compareAndSet(false, true)) {
            return;
        }

        final List<String> classes;
        try (var stream = ParallelClassPrewarmer.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                logger().warn("BOOTOPTIM_PREWARM status=missing_resource resource={}", RESOURCE);
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                classes = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        } catch (Throwable throwable) {
            logger().warn("BOOTOPTIM_PREWARM status=resource_error error={}", throwable.toString());
            return;
        }

        if (classes.isEmpty()) {
            return;
        }

        ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
        int workers = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger loaded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(workers);
        long startedNanos = System.nanoTime();

        logger().info(
                "BOOTOPTIM_PREWARM status=started classes={} workers={} loader={}",
                classes.size(),
                workers,
                gameLoader.getClass().getName());

        for (int worker = 0; worker < workers; worker++) {
            Thread.ofPlatform()
                    .daemon(true)
                    .name("bootoptim-class-prewarm-" + worker)
                    .start(() -> {
                        while (true) {
                            int index = cursor.getAndIncrement();
                            if (index >= classes.size()) {
                                break;
                            }
                            try {
                                Class.forName(classes.get(index), false, gameLoader);
                                loaded.incrementAndGet();
                            } catch (Throwable ignored) {
                                // Profiling experiment is fail-open. Vanilla will load the class normally when needed.
                                failed.incrementAndGet();
                            }
                        }

                        if (remaining.decrementAndGet() == 0) {
                            logger().info(
                                    "BOOTOPTIM_PREWARM status=finished classes={} loaded={} failed={} duration_ms={}",
                                    classes.size(),
                                    loaded.get(),
                                    failed.get(),
                                    (System.nanoTime() - startedNanos) / 1_000_000.0);
                        }
                    });
        }
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }
}
