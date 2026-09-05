package dev.wachipayox.bootoptim.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.Configuration;
import jdk.jfr.EventSettings;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/**
 * Diagnostic-only JFR capture for a single startup campaign run.
 *
 * <p>The recording starts from the bootstrap transformation service, before mod discovery and the
 * expensive transformation/model phases. It is stopped from the inner mod when the first title
 * screen is reached. The stock JFR "profile" configuration gives sampled CPU/allocation data;
 * a small set of thresholded IO/contention events is explicitly enabled so one slow-machine run
 * can distinguish CPU, disk, network, GC and blocking without per-call instrumentation.</p>
 */
final class StartupFlightRecorder {
    static final String RECORDING_NAME = "BootOptim Startup Scaling Campaign";
    static final String COMPLETE_PROPERTY = "boot_optim.profileCampaign.complete";
    static final String PATH_PROPERTY = "boot_optim.profileCampaign.jfrPath";

    private static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    private static boolean started;

    private StartupFlightRecorder() {}

    static synchronized void start(Path gameDirectory) {
        if (started || !Boolean.getBoolean(PROFILE_PROPERTY)) {
            return;
        }
        started = true;

        long setupStarted = System.nanoTime();
        try {
            Path directory = gameDirectory.resolve(".bootoptim").resolve("profiles");
            Files.createDirectories(directory);
            Path destination = directory.resolve("startup-scaling-" + System.currentTimeMillis() + ".jfr");

            Configuration configuration = Configuration.getConfiguration("profile");
            Recording recording = new Recording(configuration);
            recording.setName(RECORDING_NAME);
            recording.setToDisk(true);
            recording.setDestination(destination);
            recording.setMaxSize(256L * 1024L * 1024L);

            Set<String> eventTypes = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                    .map(EventType::getName)
                    .collect(Collectors.toSet());

            enablePeriod(recording, eventTypes, "jdk.ExecutionSample", Duration.ofMillis(10));
            enablePeriod(recording, eventTypes, "jdk.NativeMethodSample", Duration.ofMillis(20));
            enable(recording, eventTypes, "jdk.ObjectAllocationSample");
            enableThreshold(recording, eventTypes, "jdk.FileRead", Duration.ofMillis(1));
            enableThreshold(recording, eventTypes, "jdk.FileWrite", Duration.ofMillis(1));
            enableThreshold(recording, eventTypes, "jdk.SocketRead", Duration.ofMillis(10));
            enableThreshold(recording, eventTypes, "jdk.SocketWrite", Duration.ofMillis(10));
            enableThreshold(recording, eventTypes, "jdk.ThreadPark", Duration.ofMillis(2));
            enableThreshold(recording, eventTypes, "jdk.JavaMonitorEnter", Duration.ofMillis(2));
            enablePeriod(recording, eventTypes, "jdk.CPULoad", Duration.ofSeconds(1));
            enablePeriod(recording, eventTypes, "jdk.ThreadCPULoad", Duration.ofSeconds(1));
            enablePeriod(recording, eventTypes, "jdk.PhysicalMemory", Duration.ofSeconds(1));
            enablePeriod(recording, eventTypes, "jdk.ClassLoadingStatistics", Duration.ofSeconds(1));
            enablePeriod(recording, eventTypes, "jdk.CompilerStatistics", Duration.ofSeconds(1));
            enable(recording, eventTypes, "jdk.GarbageCollection");

            recording.start();
            System.setProperty(PATH_PROPERTY, destination.toAbsolutePath().toString());
            double setupMs = (System.nanoTime() - setupStarted) / 1_000_000.0D;
            System.out.printf(
                    java.util.Locale.ROOT,
                    "BOOTOPTIM_CAMPAIGN_JFR event=start path=%s setup_ms=%.3f max_mib=256 execution_sample_ms=10%n",
                    destination.toAbsolutePath(),
                    setupMs);
            StartupDiagnostics.event(
                    "CAMPAIGN_JFR",
                    "event=start path=" + destination.toAbsolutePath() + " setup_ms=" + String.format(java.util.Locale.ROOT, "%.3f", setupMs));
        } catch (Throwable failure) {
            System.out.printf(
                    "BOOTOPTIM_CAMPAIGN_JFR event=disabled reason=%s%n",
                    failure.getClass().getName());
            StartupDiagnostics.failure("startup_campaign_jfr", failure);
        }
    }

    private static void enable(Recording recording, Set<String> types, String name) {
        if (types.contains(name)) {
            recording.enable(name);
        }
    }

    private static void enableThreshold(Recording recording, Set<String> types, String name, Duration threshold) {
        if (!types.contains(name)) {
            return;
        }
        EventSettings settings = recording.enable(name);
        settings.withThreshold(threshold);
    }

    private static void enablePeriod(Recording recording, Set<String> types, String name, Duration period) {
        if (!types.contains(name)) {
            return;
        }
        EventSettings settings = recording.enable(name);
        settings.withPeriod(period);
    }
}
