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
 * Diagnostic-only MCEF ceiling that suppresses the automatic pre-title initialize() call and keeps
 * the real MCEF lifecycle deferred until a guarded CEF consumer actually appears.
 *
 * <p>The controller never fakes MCEF's initialized state, never dispatches its init hooks itself and
 * never runs JCEF on an arbitrary worker. A consumer forces the real MCEF.initialize() on the
 * Minecraft client thread before the original consumer body proceeds.</p>
 */
public final class McefFirstConsumerDeferCeiling {
    public static final String PROPERTY = "boot_optim.experimentMcefFirstConsumerDefer";

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

    private McefFirstConsumerDeferCeiling() {
    }

    /** Called from the optional MCEF mixin at MCEF.initialize() HEAD. */
    public static boolean shouldSuppressInitialize() {
        if (!ENABLED || Boolean.TRUE.equals(FORCE_INITIALIZE.get())) {
            return false;
        }
        if (!isCompatible()) {
            return false;
        }

        if (isMcefInitialized()) {
            STATE.set(State.COMPLETE);
            return false;
        }

        State state = STATE.get();
        if (state == State.COMPLETE) {
            // A delayed CefInitMixin task can arrive after a consumer already forced the real init.
            // If MCEF reports initialized, suppressing that redundant automatic call avoids double init.
            if (isMcefInitialized()) {
                LOGGER.info("BOOTOPTIM_MCEF_FIRST_CONSUMER event=suppress_redundant_init");
                return true;
            }
            return false;
        }
        if (state != State.ARMED && state != State.DEFERRED) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            STATE.set(State.ABORTED);
            LOGGER.info(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=abort reason=initialize_not_client_thread thread={}",
                    Thread.currentThread().getName());
            return false;
        }

        if (STATE.compareAndSet(State.ARMED, State.DEFERRED)) {
            firstSuppressedNanos = System.nanoTime();
            LOGGER.info(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=deferred mcef_version={} thread={}",
                    EXPECTED_VERSION,
                    Thread.currentThread().getName());
        }
        suppressedCalls++;
        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER event=suppress_auto_init count={} thread={}",
                suppressedCalls,
                Thread.currentThread().getName());
        return true;
    }

    /** Guard direct CEF consumers. */
    public static void beforeConsumer(String consumer) {
        if (!ENABLED || STATE.get() != State.DEFERRED) {
            return;
        }
        if (!STATE.compareAndSet(State.DEFERRED, State.FORCING_BY_CONSUMER)) {
            return;
        }

        long deferredNanos = firstSuppressedNanos == 0L ? 0L : System.nanoTime() - firstSuppressedNanos;
        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER event=consumer_force consumer={} defer_ms={} thread={}",
                consumer,
                formatMs(deferredNanos),
                Thread.currentThread().getName());
        forceInitializeOnClientThread("consumer:" + consumer);
    }

    /** Called immediately before the normal main-menu marker used by CI/reporting. */
    public static void onMainMenuReached() {
        if (!ENABLED || !isCompatible()) {
            return;
        }
        long deferredNanos = firstSuppressedNanos == 0L ? 0L : System.nanoTime() - firstSuppressedNanos;
        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER event=main_menu state={} initialized={} suppressed_calls={} defer_ms={}",
                STATE.get().name().toLowerCase(Locale.ROOT),
                isMcefInitialized(),
                suppressedCalls,
                formatMs(deferredNanos));
    }

    private static void forceInitializeOnClientThread(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            STATE.set(State.ABORTED);
            LOGGER.warn("BOOTOPTIM_MCEF_FIRST_CONSUMER event=abort reason=no_minecraft_instance trigger={}", reason);
            return;
        }

        if (minecraft.isSameThread()) {
            forceInitializeNow(reason);
            return;
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                forceInitializeNow(reason);
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
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=abort reason=client_thread_handoff_failed trigger={}",
                    reason,
                    exception);
        }
    }

    private static void forceInitializeNow(String reason) {
        if (isMcefInitialized()) {
            STATE.set(State.COMPLETE);
            LOGGER.info("BOOTOPTIM_MCEF_FIRST_CONSUMER event=already_initialized trigger={}", reason);
            return;
        }

        long startNanos = System.nanoTime();
        boolean result = false;
        try {
            FORCE_INITIALIZE.set(true);
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerDeferCeiling.class.getClassLoader());
            Method initialize = mcef.getMethod("initialize");
            Object value = initialize.invoke(null);
            result = value instanceof Boolean booleanValue && booleanValue;
            STATE.set(result ? State.COMPLETE : State.ABORTED);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            STATE.set(State.ABORTED);
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : exception;
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=force_init_failed trigger={} wall_ms={}",
                    reason,
                    formatMs(System.nanoTime() - startNanos),
                    cause);
            return;
        } finally {
            FORCE_INITIALIZE.remove();
        }

        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER event=force_init_complete trigger={} result={} wall_ms={} thread={}",
                reason,
                result,
                formatMs(System.nanoTime() - startNanos),
                Thread.currentThread().getName());
    }

    private static boolean isCompatible() {
        if (compatibilityChecked) {
            return compatible;
        }
        synchronized (McefFirstConsumerDeferCeiling.class) {
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
                            "BOOTOPTIM_MCEF_FIRST_CONSUMER event=disabled reason=version_probe_failed",
                            exception);
                }
            }
            compatibilityChecked = true;

            if (!compatible && !compatibilityReported) {
                compatibilityReported = true;
                LOGGER.info(
                        "BOOTOPTIM_MCEF_FIRST_CONSUMER event=disabled reason=mcef_version expected={} actual={}",
                        EXPECTED_VERSION,
                        version == null ? "absent" : version);
            } else if (compatible) {
                LOGGER.info(
                        "BOOTOPTIM_MCEF_FIRST_CONSUMER event=armed mcef_version={} mode=first_consumer_defer",
                        EXPECTED_VERSION);
            }
            return compatible;
        }
    }

    private static boolean isMcefInitialized() {
        try {
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerDeferCeiling.class.getClassLoader());
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
        DEFERRED,
        FORCING_BY_CONSUMER,
        COMPLETE,
        ABORTED
    }
}
