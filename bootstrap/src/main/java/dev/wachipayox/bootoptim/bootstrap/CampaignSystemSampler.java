package dev.wachipayox.bootoptim.bootstrap;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;

/** One-hertz, low-overhead machine/JVM telemetry for cross-machine startup scaling runs. */
final class CampaignSystemSampler {
    private static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    private static final String COMPLETE_PROPERTY = StartupFlightRecorder.COMPLETE_PROPERTY;
    private static final long SAMPLE_INTERVAL_MS = 1_000L;
    private static final long MAX_RUNTIME_MS = 15L * 60L * 1_000L;
    private static boolean started;

    private CampaignSystemSampler() {}

    static synchronized void start() {
        if (started || !Boolean.getBoolean(PROFILE_PROPERTY)) {
            return;
        }
        started = true;
        System.setProperty(COMPLETE_PROPERTY, "false");

        Thread sampler = new Thread(CampaignSystemSampler::run, "BootOptim-startup-scaling-sampler");
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();
    }

    private static void run() {
        try {
            java.lang.management.OperatingSystemMXBean genericOs = ManagementFactory.getOperatingSystemMXBean();
            OperatingSystemMXBean os = genericOs instanceof OperatingSystemMXBean extended ? extended : null;
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
            CompilationMXBean compiler = ManagementFactory.getCompilationMXBean();
            List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
            Runtime runtime = Runtime.getRuntime();

            long physicalTotal = os == null ? -1L : os.getTotalMemorySize();
            String gcNames = collectors.stream().map(GarbageCollectorMXBean::getName).reduce((a, b) -> a + "," + b).orElse("none");
            String processor = System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown");
            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_CAMPAIGN_ENV os=%s os_version=%s arch=%s processors=%d java=%s heap_max_mib=%d physical_total_mib=%d gc=%s processor=%s%n",
                    token(System.getProperty("os.name", "unknown")),
                    token(System.getProperty("os.version", "unknown")),
                    token(System.getProperty("os.arch", "unknown")),
                    runtime.availableProcessors(),
                    token(Runtime.version().toString()),
                    mib(runtime.maxMemory()),
                    mib(physicalTotal),
                    token(gcNames),
                    token(processor));

            long wallStarted = System.nanoTime();
            long previousWall = wallStarted;
            long previousCpu = processCpuTime(os);
            long previousGcCount = gcCount(collectors);
            long previousGcTime = gcTime(collectors);

            while (!Boolean.getBoolean(COMPLETE_PROPERTY)) {
                if ((System.nanoTime() - wallStarted) / 1_000_000L > MAX_RUNTIME_MS) {
                    break;
                }
                Thread.sleep(SAMPLE_INTERVAL_MS);

                long now = System.nanoTime();
                long cpu = processCpuTime(os);
                long gcCount = gcCount(collectors);
                long gcTime = gcTime(collectors);
                long wallDelta = Math.max(1L, now - previousWall);
                long cpuDelta = cpu < 0L || previousCpu < 0L ? -1L : Math.max(0L, cpu - previousCpu);
                double coresUsed = cpuDelta < 0L ? -1.0D : (double) cpuDelta / (double) wallDelta;

                var heap = memory.getHeapMemoryUsage();
                var nonHeap = memory.getNonHeapMemoryUsage();
                long compilationMs = compiler != null && compiler.isCompilationTimeMonitoringSupported()
                        ? compiler.getTotalCompilationTime() : -1L;
                long freePhysical = os == null ? -1L : os.getFreeMemorySize();
                double processLoad = os == null ? -1.0D : os.getProcessCpuLoad();
                double systemLoad = os == null ? -1.0D : os.getCpuLoad();

                System.out.printf(
                        Locale.ROOT,
                        "BOOTOPTIM_SYSTEM_SAMPLE uptime_ms=%d interval_ms=%.3f process_cpu_total_ms=%.3f process_cpu_delta_ms=%.3f cores_used=%.3f process_cpu_load=%.4f system_cpu_load=%.4f heap_used_mib=%d heap_committed_mib=%d nonheap_used_mib=%d gc_count=%d gc_count_delta=%d gc_time_ms=%d gc_time_delta_ms=%d threads=%d peak_threads=%d loaded_classes=%d jit_ms=%d physical_free_mib=%d%n",
                        ManagementFactory.getRuntimeMXBean().getUptime(),
                        wallDelta / 1_000_000.0D,
                        cpu < 0L ? -1.0D : cpu / 1_000_000.0D,
                        cpuDelta < 0L ? -1.0D : cpuDelta / 1_000_000.0D,
                        coresUsed,
                        processLoad,
                        systemLoad,
                        mib(heap.getUsed()),
                        mib(heap.getCommitted()),
                        mib(nonHeap.getUsed()),
                        gcCount,
                        delta(gcCount, previousGcCount),
                        gcTime,
                        delta(gcTime, previousGcTime),
                        threads.getThreadCount(),
                        threads.getPeakThreadCount(),
                        classes.getLoadedClassCount(),
                        compilationMs,
                        mib(freePhysical));

                previousWall = now;
                previousCpu = cpu;
                previousGcCount = gcCount;
                previousGcTime = gcTime;
            }

            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_SYSTEM_SAMPLE event=stop uptime_ms=%d reason=%s%n",
                    ManagementFactory.getRuntimeMXBean().getUptime(),
                    Boolean.getBoolean(COMPLETE_PROPERTY) ? "main_menu" : "timeout");
        } catch (Throwable failure) {
            System.out.printf("BOOTOPTIM_SYSTEM_SAMPLE event=disabled reason=%s%n", failure.getClass().getName());
        }
    }

    private static long processCpuTime(OperatingSystemMXBean os) {
        return os == null ? -1L : os.getProcessCpuTime();
    }

    private static long gcCount(List<GarbageCollectorMXBean> collectors) {
        long sum = 0L;
        for (GarbageCollectorMXBean collector : collectors) {
            long value = collector.getCollectionCount();
            if (value >= 0L) sum += value;
        }
        return sum;
    }

    private static long gcTime(List<GarbageCollectorMXBean> collectors) {
        long sum = 0L;
        for (GarbageCollectorMXBean collector : collectors) {
            long value = collector.getCollectionTime();
            if (value >= 0L) sum += value;
        }
        return sum;
    }

    private static long delta(long current, long previous) {
        return current < 0L || previous < 0L ? -1L : Math.max(0L, current - previous);
    }

    private static long mib(long bytes) {
        return bytes < 0L ? -1L : bytes / (1024L * 1024L);
    }

    private static String token(String text) {
        if (text == null) return "null";
        return text.replace(' ', '_').replace('\t', '_').replace('\r', '_').replace('\n', '_');
    }
}
