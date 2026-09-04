package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Earliest BootOptim entry point available from the mods directory.
 *
 * <p>This class intentionally depends only on JDK and ModLauncher API types because it is loaded in
 * ModLauncher's SERVICE layer, before the regular BootOptim NeoForge mod.</p>
 */
public final class EarlyStartupProbeService implements ITransformationService {
    private static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    private static final String BENCHMARK_PROPERTY = "boot_optim.benchmark.exitOnTitle";
    private static final boolean ENABLED = Boolean.getBoolean(PROFILE_PROPERTY)
            || Boolean.getBoolean(BENCHMARK_PROPERTY);

    public EarlyStartupProbeService() {
        // ModLauncher's GAMEDIR is not populated yet while SERVICE implementations are constructed.
        // Delay every filesystem decision until initialize(IEnvironment), which runs after argument parsing.
        BootOptimRuntimeInfo.version();
        mark("transformation_service_construct");
    }

    @Override
    public String name() {
        return "boot_optim_startup_probe";
    }

    @Override
    public void initialize(IEnvironment environment) {
        Path fallback = Path.of(System.getProperty("user.dir", "."));
        Path gameDirectory = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(fallback);
        boolean authoritative = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).isPresent();

        var config = BootstrapStartupConfig.initialize(gameDirectory);
        StartupDiagnostics.initialize();
        StartupDiagnostics.event(
                "STARTUP_PATH",
                "game_dir=" + config.gameDirectory() + " source=" + (authoritative ? "modlauncher" : "user_dir_fallback"));
        CacheVersioning.ensureCurrent();

        boolean scanCacheEnabled = !"false".equalsIgnoreCase(System.getProperty("boot_optim.scanCache", "true"));
        StartupDiagnostics.optimization(
                "mod_scan_cache",
                scanCacheEnabled,
                scanCacheEnabled ? "enabled_by_default" : "disabled_by_system_property");
        StartupDiagnostics.optimization(
                "async_scan_cache_write",
                scanCacheEnabled,
                scanCacheEnabled ? "enabled_with_mod_scan_cache" : "mod_scan_cache_disabled");
        StartupDiagnostics.cache("mod_scan_cache_path="
                + config.gameDirectory().resolve(".bootoptim").resolve("mod-scan-cache-v1"));
        mark("transformation_service_initialize");
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        mark("transformation_service_on_load");
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        // Diagnostic branch only. Enable Sponge Mixin's own performance profiler before the first GAME
        // class is defined, then wrap ModLauncher's stock loader/plugins with BootOptim's outer timers.
        // No transform result is changed or skipped.
        MixinInternalProfilerBridge.enable();
        TransformProfilingClassLoaderInstaller.installIfRequested();
        return List.of();
    }

    private static void mark(String phase) {
        if (!ENABLED) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        System.out.printf(
                "BOOTOPTIM_STARTUP phase=%s uptime_ms=%d processors=%d heap_used_mib=%d heap_max_mib=%d%n",
                phase,
                uptimeMs,
                runtime.availableProcessors(),
                usedBytes / (1024L * 1024L),
                runtime.maxMemory() / (1024L * 1024L));
    }
}
