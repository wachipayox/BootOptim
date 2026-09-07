package dev.wachipayox.bootoptim.compat.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private static final McefInitializationStateMachine COORDINATOR = new McefInitializationStateMachine();

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

        McefInitializationStateMachine.State state = COORDINATOR.state();
        if (state == McefInitializationStateMachine.State.COMPLETE) {
            // CefInitMixin can leave a delayed initialize task queued after a consumer forced CEF.
            // Suppress only when MCEF itself confirms that initialization actually completed.
            return isMcefInitialized();
        }
        if (state == McefInitializationStateMachine.State.FORCING_BY_CONSUMER
                || state == McefInitializationStateMachine.State.INITIALIZING) {
            // The real owner call bypasses this branch through FORCE_INITIALIZE. Any delayed stock
            // trigger racing the claimed attempt must not start a second unowned initializer.
            return true;
        }
        if (isMcefInitialized()) {
            COORDINATOR.observeReady();
            return false;
        }
        if (state != McefInitializationStateMachine.State.ARMED
                && state != McefInitializationStateMachine.State.DEFERRED) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            COORDINATOR.abortBeforeInitialization();
            LOGGER.warn(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=initialize_not_client_thread thread={}",
                    Thread.currentThread().getName());
            return false;
        }

        if (COORDINATOR.markDeferred()) {
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

        McefInitializationStateMachine.ConsumerAction action;
        try {
            action = COORDINATOR.beforeConsumer(Thread.currentThread(), isMcefInitialized());
        } catch (IllegalStateException exception) {
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=failed reason=owner_reentry_before_publication consumer={}",
                    consumer,
                    exception);
            throw exception;
        }

        if (action == McefInitializationStateMachine.ConsumerAction.BYPASS) {
            return;
        }
        if (action == McefInitializationStateMachine.ConsumerAction.WAIT) {
            awaitInFlightInitialization(consumer);
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
        if (minecraft != null
                && minecraft.isSameThread()
                && !isMcefInitialized()
                && COORDINATOR.state() == McefInitializationStateMachine.State.FORCING_BY_CONSUMER
                && COORDINATOR.initializerThread() == null) {
            // A worker may have claimed the attempt and queued its client-thread initializer. Never
            // block the client thread waiting for work queued to itself; take ownership here. The
            // queued task later observes COMPLETE and becomes a no-op.
            forceInitializeNow("concurrent-consumer:" + consumer);
            return;
        }

        try {
            // There is deliberately no lifecycle timeout here. Only the real owner may publish a
            // terminal state; a slow native initializer cannot be converted into ABORTED by a waiter.
            COORDINATOR.awaitCompletion();
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=concurrent_consumer_wait_failed consumer={}",
                    consumer,
                    exception);
        }
    }

    private static void forceInitializeOnClientThread(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            COORDINATOR.abortBeforeInitialization();
            LOGGER.warn("BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=no_minecraft_instance trigger={}", reason);
            return;
        }

        if (minecraft.isSameThread()) {
            forceInitializeNow(reason);
            return;
        }

        minecraft.execute(() -> forceInitializeNow(reason));

        try {
            // The client-thread task owns the only safe place to invoke native CEF. Wait for that
            // authoritative completion; do not invent a timeout terminal state behind it.
            COORDINATOR.awaitCompletion();
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=client_thread_handoff_failed trigger={}",
                    reason,
                    exception);
        }
    }

    private static boolean forceInitializeNow(String reason) {
        if (isMcefInitialized()) {
            COORDINATOR.observeReady();
            return true;
        }
        if (!COORDINATOR.beginInitialization(Thread.currentThread())) {
            if (isMcefInitialized()) {
                COORDINATOR.observeReady();
                return true;
            }
            try {
                return COORDINATOR.awaitCompletion();
            } catch (RuntimeException exception) {
                return false;
            }
        }

        long startNanos = System.nanoTime();
        boolean result = false;
        try {
            FORCE_INITIALIZE.set(true);
            Class<?> mcef = Class.forName(MCEF_CLASS, false, McefFirstConsumerDefer.class.getClassLoader());
            Method initialize = mcef.getMethod("initialize");
            Object value = initialize.invoke(null);
            result = value instanceof Boolean booleanValue && booleanValue;
            COORDINATOR.finishInitialization(result, null);
        } catch (Throwable throwable) {
            Throwable cause = McefInitializationStateMachine.unwrapInitializerThrowable(throwable);
            COORDINATOR.finishInitialization(false, cause);
            LOGGER.error(
                    "BOOTOPTIM_MCEF_FIRST_CONSUMER status=disabled reason=force_init_failed trigger={}",
                    reason,
                    cause);
            if (cause instanceof Error error) {
                throw error;
            }
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
}
