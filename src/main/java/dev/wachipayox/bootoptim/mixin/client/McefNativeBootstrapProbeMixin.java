package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostic-only, default-off markers around the exact MCEF/JCEF native bootstrap boundary.
 *
 * <p>This intentionally does not change CEF switches, audio, rendering, ownership, or retries. It is
 * present only to distinguish a suppressed MCEF auto-init from the first real JCEF bootstrap and to
 * observe CEF helper child-process creation while the client thread is inside the bootstrap window.</p>
 */
@Pseudo
@Mixin(targets = "com.cinemamod.mcef.CefUtil", remap = false)
abstract class McefNativeBootstrapProbeMixin {
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();
    private static final String BOOTOPTIM$PROPERTY = "boot_optim.mcefNativeBootstrapProbe";
    private static final String BOOTOPTIM$EXPECTED_VERSION = "2.1.6-1.21.1";
    private static final String BOOTOPTIM$EXPECTED_JAVA_CEF_COMMIT = "a78e832f9f13c2c688caea3d04d8b84fcd238d94";
    private static final AtomicBoolean BOOTOPTIM$WATCHING = new AtomicBoolean(false);
    private static volatile boolean BOOTOPTIM$COMPATIBILITY_CHECKED;
    private static volatile boolean BOOTOPTIM$COMPATIBLE;

    @Inject(method = "init()Z", at = @At("HEAD"), require = 0)
    private static void bootoptim$cefUtilHead(CallbackInfoReturnable<Boolean> cir) {
        if (!bootoptim$enabled()) {
            return;
        }
        bootoptim$mark("cefutil_head");
        bootoptim$startChildWatcher();
    }

    @Inject(
            method = "init()Z",
            at = @At(value = "INVOKE", target = "Lorg/cef/CefApp;startup([Ljava/lang/String;)Z", shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeStartup(CallbackInfoReturnable<Boolean> cir) {
        if (bootoptim$enabled()) bootoptim$mark("before_startup");
    }

    @Inject(
            method = "init()Z",
            at = @At(value = "INVOKE", target = "Lorg/cef/CefApp;startup([Ljava/lang/String;)Z", shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterStartup(CallbackInfoReturnable<Boolean> cir) {
        if (bootoptim$enabled()) bootoptim$mark("after_startup");
    }

    @Inject(
            method = "init()Z",
            at = @At(value = "INVOKE", target = "Lorg/cef/CefApp;getInstance([Ljava/lang/String;Lorg/cef/CefSettings;)Lorg/cef/CefApp;", shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeGetInstance(CallbackInfoReturnable<Boolean> cir) {
        if (bootoptim$enabled()) bootoptim$mark("before_get_instance");
    }

    @Inject(
            method = "init()Z",
            at = @At(value = "INVOKE", target = "Lorg/cef/CefApp;getInstance([Ljava/lang/String;Lorg/cef/CefSettings;)Lorg/cef/CefApp;", shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterGetInstance(CallbackInfoReturnable<Boolean> cir) {
        if (bootoptim$enabled()) bootoptim$mark("after_get_instance");
    }

    @Inject(
            method = "init()Z",
            at = @At(value = "INVOKE", target = "Lorg/cef/CefApp;createClient()Lorg/cef/CefClient;", shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeCreateClient(CallbackInfoReturnable<Boolean> cir) {
        if (bootoptim$enabled()) bootoptim$mark("before_create_client");
    }

    @Inject(
            method = "init()Z",
            at = @At(value = "INVOKE", target = "Lorg/cef/CefApp;createClient()Lorg/cef/CefClient;", shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterCreateClient(CallbackInfoReturnable<Boolean> cir) {
        if (bootoptim$enabled()) bootoptim$mark("after_create_client");
    }

    @Inject(method = "init()Z", at = @At("RETURN"), require = 0)
    private static void bootoptim$cefUtilReturn(CallbackInfoReturnable<Boolean> cir) {
        if (!bootoptim$enabled()) {
            return;
        }
        BOOTOPTIM$WATCHING.set(false);
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_MCEF_NATIVE_PROBE stage=cefutil_return result={} pid={} thread={}",
                cir.getReturnValue(),
                ProcessHandle.current().pid(),
                Thread.currentThread().getName());
    }

    private static boolean bootoptim$enabled() {
        return Boolean.parseBoolean(System.getProperty(BOOTOPTIM$PROPERTY, "false")) && bootoptim$compatible();
    }

    private static boolean bootoptim$compatible() {
        if (BOOTOPTIM$COMPATIBILITY_CHECKED) {
            return BOOTOPTIM$COMPATIBLE;
        }
        synchronized (McefNativeBootstrapProbeMixin.class) {
            if (!BOOTOPTIM$COMPATIBILITY_CHECKED) {
                try {
                    String version = ModList.get()
                            .getModContainerById("mcef")
                            .map(container -> container.getModInfo().getVersion().toString())
                            .orElse(null);
                    if (!BOOTOPTIM$EXPECTED_VERSION.equals(version)) {
                        BOOTOPTIM$COMPATIBLE = false;
                        BOOTOPTIM$LOGGER.warn(
                                "BOOTOPTIM_MCEF_NATIVE_PROBE status=disabled reason=mcef_version expected={} actual={}",
                                BOOTOPTIM$EXPECTED_VERSION,
                                version == null ? "absent" : version);
                    } else {
                        String javaCefCommit = bootoptim$javaCefCommit();
                        BOOTOPTIM$COMPATIBLE = BOOTOPTIM$EXPECTED_JAVA_CEF_COMMIT.equals(javaCefCommit);
                        if (!BOOTOPTIM$COMPATIBLE) {
                            BOOTOPTIM$LOGGER.warn(
                                    "BOOTOPTIM_MCEF_NATIVE_PROBE status=disabled reason=java_cef_commit expected={} actual={}",
                                    BOOTOPTIM$EXPECTED_JAVA_CEF_COMMIT,
                                    javaCefCommit == null ? "unavailable" : javaCefCommit);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    BOOTOPTIM$COMPATIBLE = false;
                    BOOTOPTIM$LOGGER.warn(
                            "BOOTOPTIM_MCEF_NATIVE_PROBE status=disabled reason=mapping_probe_failed",
                            exception);
                }
                BOOTOPTIM$COMPATIBILITY_CHECKED = true;
            }
            return BOOTOPTIM$COMPATIBLE;
        }
    }

    private static String bootoptim$javaCefCommit() throws ReflectiveOperationException {
        Class<?> mcef = Class.forName("com.cinemamod.mcef.MCEF", false, McefNativeBootstrapProbeMixin.class.getClassLoader());
        Object value = mcef.getMethod("getJavaCefCommit").invoke(null);
        return value instanceof String stringValue ? stringValue : null;
    }

    private static void bootoptim$mark(String stage) {
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_MCEF_NATIVE_PROBE stage={} pid={} thread={}",
                stage,
                ProcessHandle.current().pid(),
                Thread.currentThread().getName());
    }

    private static void bootoptim$startChildWatcher() {
        if (!BOOTOPTIM$WATCHING.compareAndSet(false, true)) {
            return;
        }
        Thread watcher = new Thread(() -> {
            Map<Long, String> previous = new LinkedHashMap<>();
            while (BOOTOPTIM$WATCHING.get()) {
                Map<Long, String> current = bootoptim$children();
                if (!current.equals(previous)) {
                    BOOTOPTIM$LOGGER.info("BOOTOPTIM_MCEF_NATIVE_PROBE stage=children children={}", current);
                    previous = current;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "BootOptim-MCEF-native-probe");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static Map<Long, String> bootoptim$children() {
        Map<Long, String> children = new LinkedHashMap<>();
        try (var stream = ProcessHandle.current().children()) {
            stream.forEach(child -> children.put(child.pid(), bootoptim$childKind(child)));
        } catch (RuntimeException exception) {
            children.put(-1L, "probe_error:" + exception.getClass().getSimpleName());
        }
        return children;
    }

    private static String bootoptim$childKind(ProcessHandle child) {
        try {
            String command = child.info().command()
                    .map(Path::of)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .orElse("unknown");
            String type = child.info().arguments()
                    .stream()
                    .flatMap(Arrays::stream)
                    .filter(argument -> argument.startsWith("--type="))
                    .findFirst()
                    .orElse("--type=browser-or-unknown");
            return command + ":" + type.substring("--type=".length());
        } catch (RuntimeException exception) {
            return "unknown:" + exception.getClass().getSimpleName();
        }
    }
}
