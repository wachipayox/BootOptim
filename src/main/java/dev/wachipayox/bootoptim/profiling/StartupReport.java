package dev.wachipayox.bootoptim.profiling;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends late game-layer information to the report created by the early bootstrap module. */
public final class StartupReport {
    private static final String ENABLED_PROPERTY = "boot_optim.startupLog.enabled";
    private static final String PATH_PROPERTY = "boot_optim.startupLog.path";
    private static final String VERSION_PROPERTY = "boot_optim.version.resolved";
    private static final Object LOCK = new Object();

    private StartupReport() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    public static void phase(String phase, long uptimeMs) {
        append("PHASE name=" + sanitize(phase) + " uptime_ms=" + uptimeMs);
    }

    /**
     * JVM-wide resource counters at a phase marker: process CPU, JIT compiler time, GC, code cache,
     * heap and loaded classes. Cumulative since JVM start; compare the same phase across runs.
     */
    public static void jvm(String phase) {
        if (!isEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder("JVM phase=").append(sanitize(phase));
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                line.append(" process_cpu_ms=").append(os.getProcessCpuTime() / 1_000_000L);
            }
            CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
            if (jit != null && jit.isCompilationTimeMonitoringSupported()) {
                line.append(" jit_ms=").append(jit.getTotalCompilationTime());
            }
            long gcCount = 0L;
            long gcMs = 0L;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(0L, gc.getCollectionCount());
                gcMs += Math.max(0L, gc.getCollectionTime());
            }
            line.append(" gc_count=").append(gcCount).append(" gc_ms=").append(gcMs);
            long codeCacheKb = 0L;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.NON_HEAP && pool.getName().startsWith("Code")) {
                    codeCacheKb += pool.getUsage().getUsed() / 1024L;
                }
            }
            line.append(" code_cache_used_kb=").append(codeCacheKb);
            line.append(" heap_used_mb=")
                    .append(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024L * 1024L));
            line.append(" classes_loaded=").append(ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
        } catch (Throwable failure) {
            line.append(" error=").append(sanitize(failure.getClass().getSimpleName()));
        }
        append(line.toString());
    }

    public static void complete(long totalStartupMs) {
        append("SUMMARY version=" + sanitize(System.getProperty(VERSION_PROPERTY, "unknown"))
                + " total_startup_ms=" + totalStartupMs
                + " status=main_menu_reached");
    }

    public static void optimization(String id, boolean enabled, String reason) {
        append("OPTIMIZATION id=" + sanitize(id)
                + " status=" + (enabled ? "enabled" : "disabled")
                + " reason=" + sanitize(reason));
    }

    public static void failure(String component, Throwable failure) {
        append("FAILURE component=" + sanitize(component)
                + " detail=" + sanitize(failure.getClass().getName() + ": " + failure.getMessage()));
    }

    private static void append(String line) {
        if (!isEnabled()) {
            return;
        }
        String configuredPath = System.getProperty(PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            try {
                Path path = Path.of(configuredPath);
                Files.createDirectories(path.getParent());
                Files.writeString(
                        path,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Startup reporting is strictly best-effort.
            }
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }
}
