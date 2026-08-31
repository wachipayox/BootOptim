package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.TransformingClassLoader;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import sun.misc.Unsafe;

/** Installs the process-local Mixin side-load cache without replacing the GAME classloader. */
final class MixinSideLoadCacheInstaller {
    private static final String ENABLE_PROPERTY = "boot_optim.mixinSideLoadCache";
    private static final String EXPECTED_MODLAUNCHER = "11.0.5";
    private static final String EXPECTED_MIXIN = "0.8.7";
    private static final String MIXIN_PLUGIN_NAME = "mixin";
    private static final String MIXIN_PLUGIN_CLASS = "org.spongepowered.asm.launch.MixinLaunchPlugin";
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static volatile boolean installed;

    private MixinSideLoadCacheInstaller() {
    }

    static boolean installIfRequested() {
        if ("false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"))) {
            StartupDiagnostics.optimization("mixin_side_load_cache", false, "disabled_by_system_property");
            return false;
        }
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return installed;
        }

        String modLauncherEvidence = identifySupportedModLauncher();
        if (modLauncherEvidence == null) {
            StartupDiagnostics.optimization("mixin_side_load_cache", false, "unsupported_or_unidentified_modlauncher");
            return false;
        }

        try {
            Launcher launcher = Launcher.INSTANCE;
            if (launcher == null) {
                throw new IllegalStateException("ModLauncher singleton is not initialized");
            }

            Unsafe unsafe = unsafe();
            LaunchPluginHandler launchPlugins = (LaunchPluginHandler) readField(
                    unsafe, launcher, Launcher.class, "launchPlugins");
            if (launchPlugins == null) {
                throw new IllegalStateException("LaunchPluginHandler is not initialized");
            }

            @SuppressWarnings("unchecked")
            Map<String, ILaunchPluginService> plugins = (Map<String, ILaunchPluginService>) readField(
                    unsafe, launchPlugins, LaunchPluginHandler.class, "plugins");
            if (plugins == null) {
                throw new IllegalStateException("Launch plugin map is not initialized");
            }

            ILaunchPluginService mixin = plugins.get(MIXIN_PLUGIN_NAME);
            if (mixin == null) {
                StartupDiagnostics.optimization("mixin_side_load_cache", false, "mixin_launch_plugin_absent");
                return false;
            }
            if (mixin instanceof CachingMixinLaunchPluginService) {
                installed = true;
                return true;
            }

            String mixinEvidence = identifySupportedMixin(mixin);
            if (mixinEvidence == null) {
                StartupDiagnostics.optimization("mixin_side_load_cache", false, "unsupported_or_unidentified_mixin");
                return false;
            }

            // Replace exactly one map entry and retain the already-initialized original plugin as delegate.
            // Mixin's transformation service has already captured/configured this object by the time our
            // transformation-service transformers() callback runs.
            CachingMixinLaunchPluginService replacement = new CachingMixinLaunchPluginService(mixin);
            plugins.put(MIXIN_PLUGIN_NAME, replacement);
            if (plugins.get(MIXIN_PLUGIN_NAME) != replacement) {
                plugins.put(MIXIN_PLUGIN_NAME, mixin);
                throw new IllegalStateException("Mixin launch plugin replacement did not stick");
            }

            installed = true;
            StartupDiagnostics.optimization("mixin_side_load_cache", true, "process_local_repeated_sideload_memoization");
            StartupDiagnostics.event(
                    "MIXIN_SIDELOAD_CACHE_INSTALLER",
                    "status=active modlauncher=" + modLauncherEvidence + " mixin=" + mixinEvidence);
            return true;
        } catch (Throwable t) {
            installed = false;
            StartupDiagnostics.optimization("mixin_side_load_cache", false, "installer_failed");
            StartupDiagnostics.failure("mixin_side_load_cache_installer", t);
            System.out.println("BOOTOPTIM_MIXIN_SIDELOAD_CACHE installer=failed type=" + t.getClass().getName());
            return false;
        }
    }

    private static String identifySupportedModLauncher() {
        String implementationVersion = TransformingClassLoader.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && implementationVersion.startsWith(EXPECTED_MODLAUNCHER)) {
            return implementationVersion;
        }

        String location = codeSource(TransformingClassLoader.class);
        if (location != null && (location.contains("/modlauncher/" + EXPECTED_MODLAUNCHER + "/")
                || location.contains("modlauncher-" + EXPECTED_MODLAUNCHER + ".jar"))) {
            return EXPECTED_MODLAUNCHER + "@codesource";
        }
        return null;
    }

    private static String identifySupportedMixin(ILaunchPluginService plugin) {
        Class<?> type = plugin.getClass();
        if (!MIXIN_PLUGIN_CLASS.equals(type.getName())) {
            return null;
        }

        String implementationVersion = type.getPackage().getImplementationVersion();
        if (implementationVersion != null && implementationVersion.contains(EXPECTED_MIXIN)) {
            return implementationVersion;
        }

        String location = codeSource(type);
        if (location != null && (location.contains("mixin." + EXPECTED_MIXIN)
                || location.contains("mixin-" + EXPECTED_MIXIN)
                || location.contains("/" + EXPECTED_MIXIN + "/"))) {
            return EXPECTED_MIXIN + "@codesource";
        }
        return null;
    }

    private static String codeSource(Class<?> type) {
        try {
            var protectionDomain = type.getProtectionDomain();
            var source = protectionDomain == null ? null : protectionDomain.getCodeSource();
            var location = source == null ? null : source.getLocation();
            return location == null ? null : location.toExternalForm();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static Object readField(Unsafe unsafe, Object owner, Class<?> declaringClass, String name)
            throws ReflectiveOperationException {
        Field field = declaringClass.getDeclaredField(name);
        return unsafe.getObject(owner, unsafe.objectFieldOffset(field));
    }
}
