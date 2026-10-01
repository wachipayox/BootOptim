package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import java.lang.management.ManagementFactory;
import java.time.Duration;
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
    private static final String P02_JVM_PROBE_PROPERTY = "boot_optim.p02JvmProbe";
    private static final boolean ENABLED = Boolean.getBoolean(PROFILE_PROPERTY)
            || Boolean.getBoolean(BENCHMARK_PROPERTY);
    private static final boolean P02_JVM_PROBE_ENABLED = Boolean.getBoolean(P02_JVM_PROBE_PROPERTY);

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
        return List.of();
    }

    private static void mark(String phase) {
        if (ENABLED) {
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
        if (P02_JVM_PROBE_ENABLED) {
            p02Snapshot(phase);
        }
    }

    /**
     * Deliberately coarse and default-off. Management-bean initialization is observer work, so runs with this
     * property are diagnostic evidence only and must never be used as clean TTMM benchmark samples.
     */
    private static void p02Snapshot(String phase) {
        try {
            var runtimeBean = ManagementFactory.getRuntimeMXBean();
            var classBean = ManagementFactory.getClassLoadingMXBean();
            var memoryBean = ManagementFactory.getMemoryMXBean();
            var threadBean = ManagementFactory.getThreadMXBean();
            long gcCount = 0L;
            long gcTimeMs = 0L;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = gc.getCollectionCount();
                long time = gc.getCollectionTime();
                if (count >= 0L) gcCount += count;
                if (time >= 0L) gcTimeMs += time;
            }
            long processCpuMs = ProcessHandle.current().info().totalCpuDuration()
                    .map(Duration::toMillis)
                    .orElse(-1L);
            var heap = memoryBean.getHeapMemoryUsage();
            System.out.printf(
                    "BOOTOPTIM_P02_JVM phase=%s uptime_ms=%d jvm_start_epoch_ms=%d process_cpu_ms=%d "
                            + "loaded_classes=%d total_loaded_classes=%d unloaded_classes=%d gc_count=%d gc_time_ms=%d "
                            + "heap_used_mib=%d heap_committed_mib=%d threads=%d%n",
                    phase,
                    runtimeBean.getUptime(),
                    runtimeBean.getStartTime(),
                    processCpuMs,
                    classBean.getLoadedClassCount(),
                    classBean.getTotalLoadedClassCount(),
                    classBean.getUnloadedClassCount(),
                    gcCount,
                    gcTimeMs,
                    heap.getUsed() / (1024L * 1024L),
                    heap.getCommitted() / (1024L * 1024L),
                    threadBean.getThreadCount());
        } catch (Throwable error) {
            // A diagnostic must never become a startup dependency. Do not log exception messages because they can
            // contain machine-local paths; the class name is enough to invalidate the diagnostic offline.
            System.out.printf("BOOTOPTIM_P02_JVM phase=%s error=%s%n", phase, error.getClass().getName());
        }
    }
}
