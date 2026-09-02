package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Diagnostic-only ceiling that moves MCEF's initialization from immediately before the first client
 * resource reload to immediately after that reload has been kicked off.
 *
 * <p>The real MCEF.initialize() still runs on the Minecraft client/render thread. Downloader state,
 * MCEF's awaiting-init callback list, browser construction, message-loop pumping and shutdown remain
 * owned by MCEF. This class only creates a short deferral window so CEF native initialization can
 * overlap the reload's asynchronous preparation work.</p>
 */
public final class McefReloadOverlapCeiling {
    public static final String PROPERTY = "boot_optim.experimentMcefReloadOverlap";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MCEF_CLASS = "com.cinemamod.mcef.MCEF";
    private static final String EXPECTED_VERSION = "2.1.6-1.21.1";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final ThreadLocal<Boolean> FORCE_INITIALIZE = ThreadLocal.withInitial(() -> false);
    private static final AtomicReference<State> STATE = new AtomicReference<>(State.ARMED);

    private static volatile boolean compatibilityChecked;
    private static volatile boolean compatible;
    private static volatile boolean compatibilityReported;
    private static volatile int suppressedCalls;
    private static volatile long firstSuppressedNanos;

    private McefReloadOverlapCeiling() {
    }

    /** Called from the optional MCEF mixin at MCEF.initialize() HEAD. */
    public static boolean shouldSuppressInitialize() {
        if (!ENABLED || Boolean.TRUE.equals(FORCE_INITIALIZE.get())) {
            return false;
        }
        if (!isCompatible()) {
            return false;
        }

        State state = STATE.get();
        if (state == State.COMPLETE && isMcefInitialized()) {
            // Delaying initialization can allow another already-queued CefInitMixin task to arrive
            // after the forced call. Do not initialize CEF twice because of the experiment.
            LOGGER.info("BOOTOPTIM_MCEF_RELOAD_OVERLAP event=suppress_redundant_init");
            return true;
        }
        if (state != State.ARMED && state != State.SUPPRESSED_WAITING_RELOAD) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            STATE.compareAndSet(State.ARMED, State.ABORTED);
            STATE.compareAndSet(State.SUPPRESSED_WAITING_RELOAD, State.ABORTED);
            LOGGER.info(
                    "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=abort reason=initialize_not_client_thread thread={}",
                    Thread.currentThread().getName());
            return false;
        }

        // MCEF 2.1.6 documents initialize() as an internal entry point called by CefInitMixin and
        // explicitly says other callers should not invoke it. Do not depend on a CefInitMixin stack
        // frame here: Mixin copies injected handlers/lambdas into Minecraft, so that source class is
        // not guaranteed to survive in the transformed runtime call stack.
        if (STATE.compareAndSet(State.ARMED, State.SUPPRESSED_WAITING_RELOAD)) {
            firstSuppressedNanos = System.nanoTime();
        }
        suppressedCalls++;
        LOGGER.info(
                "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=suppress_init count={} thread={}",
                suppressedCalls,
                Thread.currentThread().getName());
        return true;
    }

    /**
     * Called at RETURN from ReloadableResourceManager.createReload. Only the first relevant call can
     * consume the suppressed state. The stock reload has already scheduled its preparation work.
     */
    public static void afterResourceReloadStarted() {
        if (!ENABLED || !isCompatible()) {
            return;
        }
        if (!STATE.compareAndSet(State.SUPPRESSED_WAITING_RELOAD, State.FORCING_AFTER_RELOAD_START)) {
            return;
        }

        long delayNanos = firstSuppressedNanos == 0L ? 0L : System.nanoTime() - firstSuppressedNanos;
        LOGGER.info(
                "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=reload_started suppressed_calls={} defer_window_ms={}",
                suppressedCalls,
                formatMs(delayNanos));
        forceInitializeOnClientThread("reload_started", State.COMPLETE);
    }

    /**
     * Guard for direct CEF consumers during the short suppression window. If one appears, initialize
     * CEF before allowing the stock consumer body to continue.
     */
    public static void beforeConsumer(String consumer) {
        if (!ENABLED || STATE.get() != State.SUPPRESSED_WAITING_RELOAD) {
            return;
        }
        if (!STATE.compareAndSet(State.SUPPRESSED_WAITING_RELOAD, State.FORCED_BY_CONSUMER)) {
            return;
        }

        LOGGER.info(
                "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=consumer_before_reload consumer={} thread={}",
                consumer,
                Thread.currentThread().getName());
        forceInitializeOnClientThread("consumer:" + consumer, State.COMPLETE);
    }

    private static void forceInitializeOnClientThread(String reason, State successState) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            STATE.set(State.ABORTED);
            LOGGER.warn("BOOTOPTIM_MCEF_RELOAD_OVERLAP event=abort reason=no_minecraft_instance trigger={}", reason);
            return;
        }

        if (minecraft.isSameThread()) {
            forceInitializeNow(reason, successState);
            return;
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                forceInitializeNow(reason, successState);
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });

        try {
            completion.get(30L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            STATE.set(State.ABORTED);
            LOGGER.error(
                    "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=abort reason=client_thread_handoff_failed trigger={}",
                    reason,
                    exception);
        }
    }

    private static void forceInitializeNow(String reason, State successState) {
        if (isMcefInitialized()) {
            STATE.set(successState);
            LOGGER.info("BOOTOPTIM_MCEF_RELOAD_OVERLAP event=already_initialized trigger={}", reason);
            return;
        }

        long startNanos = System.nanoTime();
        boolean result = false;
        try {
            FORCE_INITIALIZE.set(true);
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefReloadOverlapCeiling.class.getClassLoader());
            Method initialize = mcef.getMethod("initialize");
            Object value = initialize.invoke(null);
            result = value instanceof Boolean booleanValue && booleanValue;
            STATE.set(result ? successState : State.ABORTED);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            STATE.set(State.ABORTED);
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : exception;
            LOGGER.error(
                    "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=force_init_failed trigger={} wall_ms={}",
                    reason,
                    formatMs(System.nanoTime() - startNanos),
                    cause);
            return;
        } finally {
            FORCE_INITIALIZE.remove();
        }

        LOGGER.info(
                "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=force_init_complete trigger={} result={} wall_ms={} thread={}",
                reason,
                result,
                formatMs(System.nanoTime() - startNanos),
                Thread.currentThread().getName());
    }

    private static boolean isCompatible() {
        if (compatibilityChecked) {
            return compatible;
        }
        synchronized (McefReloadOverlapCeiling.class) {
            if (compatibilityChecked) {
                return compatible;
            }

            String version = null;
            try {
                version = ModList.get()
                        .getModContainerById("mcef")
                        .map(container -> container.getModInfo().getVersion().toString())
                        .orElse(null);
                compatible = EXPECTED_VERSION.equals(version);
            } catch (RuntimeException exception) {
                compatible = false;
                if (!compatibilityReported) {
                    compatibilityReported = true;
                    LOGGER.warn(
                            "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=disabled reason=version_probe_failed",
                            exception);
                }
            }
            compatibilityChecked = true;

            if (!compatible && !compatibilityReported) {
                compatibilityReported = true;
                LOGGER.info(
                        "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=disabled reason=mcef_version expected={} actual={}",
                        EXPECTED_VERSION,
                        version == null ? "absent" : version);
            } else if (compatible) {
                LOGGER.info(
                        "BOOTOPTIM_MCEF_RELOAD_OVERLAP event=armed mcef_version={} mode=initial_reload_overlap",
                        EXPECTED_VERSION);
            }
            return compatible;
        }
    }

    private static boolean isMcefInitialized() {
        try {
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefReloadOverlapCeiling.class.getClassLoader());
            Method isInitialized = mcef.getMethod("isInitialized");
            return Boolean.TRUE.equals(isInitialized.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private enum State {
        ARMED,
        SUPPRESSED_WAITING_RELOAD,
        FORCING_AFTER_RELOAD_START,
        FORCED_BY_CONSUMER,
        COMPLETE,
        ABORTED
    }
}
