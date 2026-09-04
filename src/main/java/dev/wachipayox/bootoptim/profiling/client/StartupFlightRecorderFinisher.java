package dev.wachipayox.bootoptim.profiling.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stops the bootstrap-owned startup campaign JFR at the exact first-title-screen boundary. */
public final class StartupFlightRecorderFinisher {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/StartupCampaign");
    private static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    private static final String RECORDING_NAME = "BootOptim Startup Scaling Campaign";
    private static final String COMPLETE_PROPERTY = "boot_optim.profileCampaign.complete";
    private static final String PATH_PROPERTY = "boot_optim.profileCampaign.jfrPath";
    private static final AtomicBoolean FINISHED = new AtomicBoolean();

    private StartupFlightRecorderFinisher() {}

    public static void finishAtMainMenu() {
        if (!Boolean.getBoolean(PROFILE_PROPERTY) || !FINISHED.compareAndSet(false, true)) {
            return;
        }

        long started = System.nanoTime();
        Path destination = null;
        String result = "not_found";
        try {
            for (Recording recording : FlightRecorder.getFlightRecorder().getRecordings()) {
                if (!RECORDING_NAME.equals(recording.getName())) {
                    continue;
                }
                destination = recording.getDestination();
                if (recording.getState() == RecordingState.RUNNING) {
                    recording.stop();
                }
                result = recording.getState().name().toLowerCase(Locale.ROOT);
                recording.close();
                break;
            }
        } catch (Throwable failure) {
            result = "failed_" + failure.getClass().getSimpleName();
            LOGGER.warn("Unable to stop startup scaling JFR cleanly", failure);
        } finally {
            System.setProperty(COMPLETE_PROPERTY, "true");
        }

        if (destination == null) {
            String configured = System.getProperty(PATH_PROPERTY);
            if (configured != null && !configured.isBlank()) {
                try {
                    destination = Path.of(configured);
                } catch (RuntimeException ignored) {
                    // Diagnostic path only.
                }
            }
        }

        long bytes = -1L;
        if (destination != null) {
            try {
                if (Files.isRegularFile(destination)) {
                    bytes = Files.size(destination);
                }
            } catch (Throwable ignored) {
                // Size reporting is best effort only.
            }
        }
        double stopMs = (System.nanoTime() - started) / 1_000_000.0D;
        LOGGER.info(
                "BOOTOPTIM_CAMPAIGN_JFR event=stop result={} path={} size_mib={} stop_ms={}",
                result,
                destination == null ? "unknown" : destination.toAbsolutePath(),
                bytes < 0L ? "-1" : String.format(Locale.ROOT, "%.3f", bytes / (1024.0D * 1024.0D)),
                String.format(Locale.ROOT, "%.3f", stopMs));
    }
}
