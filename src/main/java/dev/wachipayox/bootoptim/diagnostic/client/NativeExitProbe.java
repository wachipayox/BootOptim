package dev.wachipayox.bootoptim.diagnostic.client;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/** Default-off lifecycle marker for forensic native-termination runs. */
public final class NativeExitProbe {
    public static final String PROPERTY = "boot_optim.mcefNativeBootstrapProbe";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private NativeExitProbe() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    public static void mark(String stage) {
        if (!isEnabled()) {
            return;
        }
        String marker = "BOOTOPTIM_NATIVE_EXIT_PROBE stage="
                + stage
                + " pid="
                + ProcessHandle.current().pid()
                + " thread="
                + Thread.currentThread().getName();
        // System.err provides a second path if the logging backend is already stopping or being torn down.
        System.err.println(marker);
        try {
            LOGGER.info(marker);
        } catch (RuntimeException ignored) {
            // Diagnostic marker must never interfere with normal client or JVM shutdown.
        }
    }

    public static void installIfEnabled() {
        if (!isEnabled() || !INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> mark("jvm_shutdown_hook"),
                    "BootOptim-native-exit-probe"));
            LOGGER.info(
                    "BOOTOPTIM_NATIVE_EXIT_PROBE stage=installed pid={} kill_switch=-D{}=false",
                    ProcessHandle.current().pid(),
                    PROPERTY);
        } catch (RuntimeException exception) {
            INSTALLED.set(false);
            LOGGER.warn("BOOTOPTIM_NATIVE_EXIT_PROBE status=disabled reason=shutdown_hook_install_failed", exception);
        }
    }
}
