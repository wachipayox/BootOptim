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
 * Experimental ceiling for deferring MCEF 2.1.6 native CEF initialization until the first real
 * consumer, with a conservative pre-world fallback.
 *
 * <p>This class never fabricates MCEF's initialized state and never invokes MCEF init listeners
 * directly. The real {@code MCEF.initialize()} remains authoritative and runs on Minecraft's client
 * thread. FancyMenu/WebDisplays/etc. continue registering their normal {@code scheduleForInit}
 * callbacks while CEF is deferred.</p>
 */
public final class McefFirstConsumerCeiling {
    public static final String PROPERTY = "boot_optim.experimentMcefFirstConsumer";

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
    private static volatile String forceReason = "none";

    private McefFirstConsumerCeiling() {
    }

    /** Called from the optional MCEF mixin at {@code MCEF.initialize()} HEAD. */
    public static boolean shouldSuppressAutomaticInitialize() {
        if (!ENABLED || Boolean.TRUE.equals(FORCE_INITIALIZE.get()) || !isCompatible()) {
            return false;
        }

        if (isMcefInitialized()) {
            STATE.set(State.READY);
            // CefInitMixin can have already queued more than one delayed task while MCEF was still
            // false. Never let one of those stale tasks initialize CEF twice.
            LOGGER.info("BOOTOPTIM_MCEF_FIRST_CONSUMER event=suppress_redundant_init state=READY");
            return true;
        }

        for (;;) {
            State state = STATE.get();
            if (state == State.READY || state == State.ABORTED) {
                return false;
            }
            if (state == State.FORCING) {
                // A second already-queued automatic call must not race the one real forced init.
                LOGGER.info("BOOTOPTIM_MCEF_FIRST_CONSUMER event=suppress_parallel_init state=FORCING");
                return true;
            }
            if (state == State.ARMED) {
                if (!STATE.compareAndSet(State.ARMED, State.DEFERRED)) {
                    continue;
                }
                firstSuppressedNanos = System.nanoTime();
            }

            suppressedCalls++;
            LOGGER.info(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=suppress_auto_init count={} thread={}",
                    suppressedCalls,
                    Thread.currentThread().getName());
            return true;
        }
    }

    /** Guard for MCEF APIs that require the client/app/browser objects to exist. */
    public static void beforeConsumer(String consumer) {
        if (!ENABLED || Boolean.TRUE.equals(FORCE_INITIALIZE.get()) || !isCompatible()) {
            return;
        }
        if (isMcefInitialized()) {
            STATE.set(State.READY);
            return;
        }

        for (;;) {
            State current = STATE.get();
            if (current == State.ABORTED || current == State.FORCING || current == State.READY) {
                return;
            }
            if (!STATE.compareAndSet(current, State.FORCING)) {
                continue;
            }

            forceReason = "consumer:" + consumer;
            LOGGER.info(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=first_consumer consumer={} previous_state={} thread={}",
                    consumer,
                    current,
                    Thread.currentThread().getName());
            forceInitializeOnClientThread(forceReason);
            return;
        }
    }

    /**
     * Conservative compatibility boundary: CEF must be initialized before a real ClientLevel becomes
     * active even when no browser API was needed on the title screen.
     */
    public static void beforeWorldJoin() {
        if (!ENABLED || !isCompatible()) {
            return;
        }
        if (isMcefInitialized()) {
            STATE.set(State.READY);
            return;
        }

        for (;;) {
            State current = STATE.get();
            if (current == State.ABORTED || current == State.FORCING || current == State.READY) {
                return;
            }
            if (!STATE.compareAndSet(current, State.FORCING)) {
                continue;
            }

            forceReason = "world_join";
            LOGGER.info(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=world_boundary previous_state={} thread={}",
                    current,
                    Thread.currentThread().getName());
            forceInitializeOnClientThread(forceReason);
            return;
        }
    }

    /** Called by BootOptim's existing title-screen startup hook for ceiling attribution. */
    public static void onMainMenuReached() {
        if (!ENABLED) {
            return;
        }
        boolean initialized = isMcefInitialized();
        long deferredNanos = firstSuppressedNanos == 0L ? 0L : System.nanoTime() - firstSuppressedNanos;
        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER event=main_menu state={} initialized={} suppressed_auto_init={} force_reason={} defer_window_ms={} thread={}",
                STATE.get(),
                initialized,
                suppressedCalls,
                forceReason,
                formatMs(deferredNanos),
                Thread.currentThread().getName());
    }

    private static void forceInitializeOnClientThread(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            abort("no_minecraft_instance", reason, null);
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
            abort("client_thread_handoff_failed", reason, exception);
        }
    }

    private static void forceInitializeNow(String reason) {
        if (isMcefInitialized()) {
            STATE.set(State.READY);
            LOGGER.info("BOOTOPTIM_MCEF_FIRST_CONSUMER event=already_initialized trigger={}", reason);
            return;
        }

        long startNanos = System.nanoTime();
        boolean result;
        try {
            FORCE_INITIALIZE.set(true);
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerCeiling.class.getClassLoader());
            Method initialize = mcef.getMethod("initialize");
            Object value = initialize.invoke(null);
            result = value instanceof Boolean booleanValue && booleanValue;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : exception;
            abort("force_init_failed", reason, cause);
            return;
        } finally {
            FORCE_INITIALIZE.remove();
        }

        STATE.set(result ? State.READY : State.ABORTED);
        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER event=force_init_complete trigger={} result={} wall_ms={} thread={}",
                reason,
                result,
                formatMs(System.nanoTime() - startNanos),
                Thread.currentThread().getName());
    }

    private static void abort(String reason, String trigger, Throwable failure) {
        STATE.set(State.ABORTED);
        if (failure == null) {
            LOGGER.warn(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=abort reason={} trigger={} thread={}",
                    reason,
                    trigger,
                    Thread.currentThread().getName());
        } else {
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER event=abort reason={} trigger={} thread={}",
                    reason,
                    trigger,
                    Thread.currentThread().getName(),
                    failure);
        }
    }

    private static boolean isCompatible() {
        if (compatibilityChecked) {
            return compatible;
        }
        synchronized (McefFirstConsumerCeiling.class) {
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
                        "BOOTOPTIM_MCEF_FIRST_CONSUMER event=armed mcef_version={} mode=first_consumer_ceiling",
                        EXPECTED_VERSION);
            }
            return compatible;
        }
    }

    private static boolean isMcefInitialized() {
        try {
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerCeiling.class.getClassLoader());
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
        FORCING,
        READY,
        ABORTED
    }
}
