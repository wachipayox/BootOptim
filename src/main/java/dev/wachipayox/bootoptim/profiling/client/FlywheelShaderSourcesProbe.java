package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Diagnostic-only attribution for Flywheel 1.0.6's synchronous program reloader.
 *
 * <p>This probe never constructs, caches, replaces, or publishes Flywheel objects. It only records
 * the existing {@code ShaderSources(ResourceManager)} constructor and the surrounding stock commit.
 * The candidate premise can therefore be rejected without introducing a second listener or a second
 * {@code ShaderSources} construction.</p>
 */
public final class FlywheelShaderSourcesProbe {
    public static final String PROPERTY = "boot_optim.profileFlywheelShaderSources";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXPECTED_VERSION = "1.0.6";
    private static final boolean REQUESTED = Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    private static final AtomicLong FLYWHEEL_GENERATION = new AtomicLong();
    private static final AtomicLong RELOAD_INSTANCE_GENERATION = new AtomicLong();
    private static final AtomicBoolean TITLE_PRESENTED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_WORLD_RENDER = new AtomicBoolean();
    private static final ThreadLocal<CommitContext> COMMIT_CONTEXT = new ThreadLocal<>();

    private static volatile boolean compatibilityChecked;
    private static volatile boolean compatible;
    private static volatile boolean compatibilityReported;
    private static volatile boolean titleOpened;

    private FlywheelShaderSourcesProbe() {
    }

    public static boolean enabled() {
        return REQUESTED && isCompatible();
    }

    public static long beginCommit(ResourceManager manager) {
        if (!enabled()) {
            return 0L;
        }
        long generation = FLYWHEEL_GENERATION.incrementAndGet();
        CommitContext context = new CommitContext(generation, manager, System.nanoTime());
        COMMIT_CONTEXT.set(context);
        LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_commit_start generation={} manager_id={} thread={}",
                generation,
                managerId(manager),
                Thread.currentThread().getName());
        return generation;
    }

    public static void endCommit(ResourceManager manager, long generation) {
        if (generation == 0L) {
            return;
        }
        CommitContext context = COMMIT_CONTEXT.get();
        try {
            boolean managerMatches = context != null && context.manager == manager;
            long started = context == null ? 0L : context.commitStartNanos;
            double elapsedMs = started == 0L ? -1.0D : (System.nanoTime() - started) / 1_000_000.0D;
            int constructors = context == null ? 0 : context.sourceConstructors;
            LOGGER.info(
                    "BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_commit_end generation={} manager_id={} manager_match={} shader_sources_constructors={} wall_ms={} thread={}",
                    generation,
                    managerId(manager),
                    managerMatches,
                    constructors,
                    format(elapsedMs),
                    Thread.currentThread().getName());
        } finally {
            COMMIT_CONTEXT.remove();
        }
    }

    public static void sourcesPrepareStart(ResourceManager manager) {
        if (!enabled()) {
            return;
        }
        CommitContext context = COMMIT_CONTEXT.get();
        long generation = context == null ? 0L : context.generation;
        boolean managerMatches = context != null && context.manager == manager;
        if (context != null) {
            context.sourceConstructors++;
            context.sourcesStartNanos = System.nanoTime();
        }
        LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_sources_prepare_start generation={} manager_id={} manager_match={} callsite={} thread={}",
                generation,
                managerId(manager),
                managerMatches,
                findFlywheelCallsite(),
                Thread.currentThread().getName());
    }

    public static void sourcesPrepareEnd(ResourceManager manager) {
        if (!enabled()) {
            return;
        }
        CommitContext context = COMMIT_CONTEXT.get();
        long generation = context == null ? 0L : context.generation;
        boolean managerMatches = context != null && context.manager == manager;
        long started = context == null ? 0L : context.sourcesStartNanos;
        double elapsedMs = started == 0L ? -1.0D : (System.nanoTime() - started) / 1_000_000.0D;
        LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_sources_prepare_end generation={} manager_id={} manager_match={} wall_ms={} thread={}",
                generation,
                managerId(manager),
                managerMatches,
                format(elapsedMs),
                Thread.currentThread().getName());
    }

    public static void observeReloadInstance(
            CompletableFuture<?> allPreparations,
            CompletableFuture<?> allDone) {
        if (!enabled()) {
            return;
        }
        long reloadGeneration = RELOAD_INSTANCE_GENERATION.incrementAndGet();
        long attachedNanos = System.nanoTime();
        allPreparations.whenComplete((unused, throwable) -> LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=allPreparations reload_generation={} status={} since_attach_ms={} thread={}",
                reloadGeneration,
                throwable == null ? "success" : "failed",
                format((System.nanoTime() - attachedNanos) / 1_000_000.0D),
                Thread.currentThread().getName()));
        allDone.whenComplete((unused, throwable) -> LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=allDone reload_generation={} status={} since_attach_ms={} thread={}",
                reloadGeneration,
                throwable == null ? "success" : "failed",
                format((System.nanoTime() - attachedNanos) / 1_000_000.0D),
                Thread.currentThread().getName()));
    }

    public static void onTitleOpened() {
        if (enabled()) {
            titleOpened = true;
        }
    }

    /** In diagnostic mode, let one actual display update happen before benchmark auto-exit. */
    public static boolean waitForPresentedTitle() {
        return enabled();
    }

    public static boolean markPresentedTitle() {
        if (!enabled() || !titleOpened || !TITLE_PRESENTED.compareAndSet(false, true)) {
            return false;
        }
        LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=main_menu_presented thread={}",
                Thread.currentThread().getName());
        return true;
    }

    public static void markFirstWorldRender() {
        if (!enabled() || !FIRST_WORLD_RENDER.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info(
                "BOOTOPTIM_FLYWHEEL_PROBE marker=first_world_render thread={}",
                Thread.currentThread().getName());
    }

    private static boolean isCompatible() {
        if (compatibilityChecked) {
            return compatible;
        }
        synchronized (FlywheelShaderSourcesProbe.class) {
            if (compatibilityChecked) {
                return compatible;
            }
            String actual = null;
            try {
                actual = ModList.get()
                        .getModContainerById("flywheel")
                        .map(container -> container.getModInfo().getVersion().toString())
                        .orElse(null);
                compatible = EXPECTED_VERSION.equals(actual);
            } catch (RuntimeException exception) {
                compatible = false;
                if (!compatibilityReported) {
                    compatibilityReported = true;
                    LOGGER.warn(
                            "BOOTOPTIM_FLYWHEEL_PROBE status=disabled reason=version_probe_failed",
                            exception);
                }
            }
            compatibilityChecked = true;
            if (!compatible && REQUESTED && !compatibilityReported) {
                compatibilityReported = true;
                LOGGER.info(
                        "BOOTOPTIM_FLYWHEEL_PROBE status=disabled reason=flywheel_version expected={} actual={}",
                        EXPECTED_VERSION,
                        actual == null ? "absent" : actual);
            } else if (compatible && REQUESTED) {
                LOGGER.info(
                        "BOOTOPTIM_FLYWHEEL_PROBE status=enabled flywheel_version={} behavior=diagnostic_only",
                        EXPECTED_VERSION);
            }
            return compatible;
        }
    }

    private static String managerId(ResourceManager manager) {
        return Integer.toUnsignedString(System.identityHashCode(manager));
    }

    private static String findFlywheelCallsite() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            if ("dev.engine_room.flywheel.backend.compile.FlwPrograms".equals(frame.getClassName())) {
                return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unresolved";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class CommitContext {
        private final long generation;
        private final ResourceManager manager;
        private final long commitStartNanos;
        private int sourceConstructors;
        private long sourcesStartNanos;

        private CommitContext(long generation, ResourceManager manager, long commitStartNanos) {
            this.generation = generation;
            this.manager = manager;
            this.commitStartNanos = commitStartNanos;
        }
    }
}
