package dev.wachipayox.bootoptim.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves only Connector's Fabric-loader preparation ahead of Minecraft client construction.
 *
 * <p>Connector 1.21.x normally calls {@code ConnectorLoader.setup()} from a mixin partway through
 * {@code Minecraft}'s constructor. That is late enough for the first client resource-pack setup to
 * observe an incompletely prepared Fabric loader when startup scheduling is tightened. BootOptim
 * performs the same setup once at constructor entry, while leaving Connector's normal
 * {@code ConnectorLoader.load()} entrypoint timing untouched.</p>
 *
 * <p>This class has no compile-time Connector dependency. If Connector is absent, the compatibility
 * path is a no-op. If the early setup fails, the completion flag remains false so Connector's own
 * later setup call is allowed to run normally.</p>
 */
public final class ConnectorResourceCompat {
    private static final String CONNECTOR_LOADER_CLASS = "org.sinytra.connector.mod.ConnectorLoader";
    private static final AtomicBoolean SETUP_COMPLETE = new AtomicBoolean();
    private static final AtomicBoolean GUARD_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private ConnectorResourceCompat() {}

    /** Called from the BootOptim Minecraft transformer at constructor entry. */
    public static void beforeMinecraftConstruction() {
        if (SETUP_COMPLETE.get() || !ATTEMPTED.compareAndSet(false, true)) {
            return;
        }

        long startedNanos = System.nanoTime();
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> connectorLoader;
            try {
                connectorLoader = Class.forName(CONNECTOR_LOADER_CLASS, false, loader);
            } catch (ClassNotFoundException absent) {
                // Connector is optional. Keep the non-Connector startup path completely untouched.
                return;
            }

            // Loading ConnectorLoader should have passed through our second transformer target. Do
            // not invoke setup early unless we know its normal later invocation has been guarded.
            if (!GUARD_INSTALLED.get()) {
                StartupDiagnostics.event(
                        "CONNECTOR_RESOURCES",
                        "result=guard_unavailable action=leave_connector_order_unchanged");
                return;
            }

            Method setup = connectorLoader.getDeclaredMethod("setup");
            if (!setup.canAccess(null)) {
                setup.setAccessible(true);
            }
            setup.invoke(null);

            // The transformed normal return also marks this. Keep this assignment as a defensive
            // backstop if Connector changes the method shape but the invocation still succeeds.
            SETUP_COMPLETE.set(true);
            StartupDiagnostics.event(
                    "CONNECTOR_RESOURCES",
                    "result=prepared_before_minecraft_constructor duration_ms=" + elapsedMillis(startedNanos));
        } catch (InvocationTargetException failure) {
            // Preserve Connector's original fallback: its later setup invocation is not suppressed.
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            StartupDiagnostics.failure("connector_resource_early_setup", cause);
        } catch (ReflectiveOperationException | LinkageError failure) {
            StartupDiagnostics.failure("connector_resource_compat", failure);
        }
    }

    /** Injected at the beginning of ConnectorLoader.setup(). */
    public static boolean shouldSkipConnectorSetup() {
        return SETUP_COMPLETE.get();
    }

    /** Injected before every normal return from ConnectorLoader.setup(). */
    public static void markConnectorSetupCompleted() {
        SETUP_COMPLETE.set(true);
    }

    /** Called by the transformer itself once the setup guard was applied successfully. */
    static void markSetupGuardInstalled() {
        GUARD_INSTALLED.set(true);
    }

    static boolean isSetupCompleteForTest() {
        return SETUP_COMPLETE.get();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
