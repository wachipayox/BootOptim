package dev.wachipayox.bootoptim.compat.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Defers MCEF's automatic pre-title CEF initialization until a real guarded consumer needs it.
 *
 * <p>The real MCEF lifecycle remains authoritative: BootOptim never fakes initialized state,
 * dispatches MCEF hooks itself, or initializes JCEF on a worker. The first guarded consumer forces
 * the real {@code MCEF.initialize()} on Minecraft's client thread before continuing.</p>
 */
public final class McefFirstConsumerDefer {
    public static final String PROPERTY = "boot_optim.mcefFirstConsumerDefer";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MCEF_CLASS = "com.cinemamod.mcef.MCEF";
    private static final String FANCY_MCEF_UTIL_CLASS = "de.keksuccino.fancymenu.util.mcef.MCEFUtil";
    private static final String EXPECTED_VERSION = "2.1.6-1.21.1";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    private static final ThreadLocal<Boolean> FORCE_INITIALIZE = ThreadLocal.withInitial(() -> false);
    private static final AtomicReference<State> STATE = new AtomicReference<>(State.ARMED);
    private static final CompletableFuture<Boolean> INITIALIZATION_COMPLETION = new CompletableFuture<>();

    private static volatile boolean compatibilityChecked;
    private static volatile boolean compatible;
    private static volatile boolean compatibilityReported;
    private static volatile int suppressedCalls;

    private McefFirstConsumerDefer() {
    }

    /** Called from the optional MCEF mixin at {@code MCEF.initialize()} HEAD. */
    public static boolean shouldSuppressInitialize() {
        if (!ENABLED || Boolean.TRUE.equals(FORCE_INITIALIZE.get()) || !isCompatible()) {
            return false;
        }

        State state = STATE.get();
        if (state == State.COMPLETE) {
            // CefInitMixin can leave a delayed initialize task queued after a consumer forced CEF.
            // Suppress only when MCEF itself confirms that initialization actually completed.
            return isMcefInitialized();
        }
        if (isMcefInitialized()) {
            STATE.set(State.COMPLETE);
            INITIALIZATION_COMPLETION.complete(true);
            return false;
        }
        if (state != State.ARMED && state != State.DEFERRED) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            STATE.set(State.ABORTED);
            INITIALIZATION_COMPLETION.complete(false);
            LOGGER.warn(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=initialize_not_client_thread thread={}",
                    Thread.currentThread().getName());
            return false;
        }

        if (STATE.compareAndSet(State.ARMED, State.DEFERRED)) {
            LOGGER.info(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=deferred mcef_version={} kill_switch=-D{}=false",
                    EXPECTED_VERSION,
                    PROPERTY);
        }
        suppressedCalls++;
        return true;
    }

    /** Guard a real CEF consumer and synchronously initialize MCEF if this is the first one. */
    public static void beforeConsumer(String consumer) {
        if (!ENABLED) {
            return;
        }

        State state = STATE.get();
        if (state == State.FORCING_BY_CONSUMER) {
            awaitInFlightInitialization(consumer);
            return;
        }
        if (state != State.DEFERRED) {
            return;
        }
        if (!STATE.compareAndSet(State.DEFERRED, State.FORCING_BY_CONSUMER)) {
            if (STATE.get() == State.FORCING_BY_CONSUMER) {
                awaitInFlightInitialization(consumer);
            }
            return;
        }

        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER status=initializing consumer={} suppressed_auto_init_calls={} thread={}",
                consumer,
                suppressedCalls,
                Thread.currentThread().getName());
        forceInitializeOnClientThread("consumer:" + consumer);
    }

    /**
     * FancyMenu keeps a separate readiness flag and updates it after MCEF's normal init callbacks.
     * Consumers that need a retry wait for that real bridge instead of BootOptim forging readiness.
     */
    public static boolean isFancyMenuMcefBridgeReady() {
        if (!ENABLED || !isCompatible() || !isMcefInitialized()) {
            return false;
        }
        try {
            Class<?> util = Class.forName(
                    FANCY_MCEF_UTIL_CLASS,
                    false,
                    McefFirstConsumerDefer.class.getClassLoader());
            Field initialized = util.getField("MCEF_initialized");
            return initialized.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static void awaitInFlightInitialization(String consumer) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.isSameThread() && !isMcefInitialized()) {
            // A worker may have won the state transition and queued its client-thread initializer.
            // Never block the client thread waiting for work queued to that same thread; perform the
            // authoritative real initializer here. The queued task will later observe initialized=true.
            boolean result = forceInitializeNow("concurrent-consumer:" + consumer);
            INITIALIZATION_COMPLETION.complete(result);
            return;
        }

        try {
            INITIALIZATION_COMPLETION.get(30L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            STATE.set(State.ABORTED);
            INITIALIZATION_COMPLETION.complete(false);
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=concurrent_consumer_wait_failed consumer={}",
                    consumer,
                    exception);
        }
    }

    private static void forceInitializeOnClientThread(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            STATE.set(State.ABORTED);
            INITIALIZATION_COMPLETION.complete(false);
            LOGGER.warn("BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=no_minecraft_instance trigger={}", reason);
            return;
        }

        if (minecraft.isSameThread()) {
            INITIALIZATION_COMPLETION.complete(forceInitializeNow(reason));
            return;
        }

        minecraft.execute(() -> {
            try {
                INITIALIZATION_COMPLETION.complete(forceInitializeNow(reason));
            } catch (Throwable throwable) {
                STATE.set(State.ABORTED);
                INITIALIZATION_COMPLETION.completeExceptionally(throwable);
            }
        });

        try {
            INITIALIZATION_COMPLETION.get(30L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            STATE.set(State.ABORTED);
            INITIALIZATION_COMPLETION.complete(false);
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=client_thread_handoff_failed trigger={}",
                    reason,
                    exception);
        }
    }

    private static boolean forceInitializeNow(String reason) {
        if (isMcefInitialized()) {
            STATE.set(State.COMPLETE);
            return true;
        }

        long startNanos = System.nanoTime();
        boolean result = false;
        try {
            FORCE_INITIALIZE.set(true);
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerDefer.class.getClassLoader());
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
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=force_init_failed trigger={}",
                    reason,
                    cause);
            return false;
        } finally {
            FORCE_INITIALIZE.remove();
        }

        LOGGER.info(
                "BOOTOPTIM_MCEF_FIRST_CONSUMER status=initialized trigger={} result={} wall_ms={} thread={}",
                reason,
                result,
                String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000.0D),
                Thread.currentThread().getName());
        return result;
    }

    private static boolean isCompatible() {
        if (compatibilityChecked) {
            return compatible;
        }
        synchronized (McefFirstConsumerDefer.class) {
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
                            "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=version_probe_failed",
                            exception);
                }
            }
            compatibilityChecked = true;

            if (!compatible && !compatibilityReported) {
                compatibilityReported = true;
                LOGGER.info(
                        "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=mcef_version expected={} actual={}",
                        EXPECTED_VERSION,
                        version == null ? "absent" : version);
            }
            return compatible;
        }
    }

    private static boolean isMcefInitialized() {
        try {
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerDefer.class.getClassLoader());
            Method isInitialized = mcef.getMethod("isInitialized");
            return Boolean.TRUE.equals(isInitialized.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private enum State {
        ARMED,
        DEFERRED,
        FORCING_BY_CONSUMER,
        COMPLETE,
        ABORTED
    }
}
