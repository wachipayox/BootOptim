package dev.wachipayox.bootoptim.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import net.neoforged.fml.loading.FMLPaths;

/** Profile-only JFR window covering root through dependency discovery. No event is read while startup is timed. */
final class DiscoveryJfrProfiler {
    private static Recording recording;
    private static boolean hookInstalled;

    private DiscoveryJfrProfiler() {}

    static synchronized void begin() {
        if (!DiscoveryProfiler.artifactDetailEnabled() || recording != null) return;
        try {
            Recording r = new Recording();
            r.setName("BootOptim Agent106 discovery attribution");
            r.setToDisk(true);
            r.enable("jdk.FileRead").withThreshold(Duration.ofMillis(1)).withStackTrace();
            r.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(1)).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(2)).withStackTrace();
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(2)).withStackTrace();
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
            r.start();
            recording = r;
            if (!hookInstalled) {
                hookInstalled = true;
                Runtime.getRuntime().addShutdownHook(new Thread(DiscoveryJfrProfiler::summarizeAfterExit, "BootOptim-Agent106-Discovery-JFR"));
            }
            System.out.println("BOOTOPTIM_DISCOVERY_JFR state=started boundary=root_begin");
        } catch (Throwable failure) {
            System.out.printf("BOOTOPTIM_DISCOVERY_JFR state=failed action=start type=%s%n", failure.getClass().getName());
        }
    }

    static synchronized void end() {
        Recording r = recording;
        if (r == null || !"RUNNING".equals(r.getState().name())) return;
        try {
            r.stop();
            System.out.println("BOOTOPTIM_DISCOVERY_JFR state=stopped boundary=dependency_end");
        } catch (Throwable failure) {
            System.out.printf("BOOTOPTIM_DISCOVERY_JFR state=failed action=stop type=%s%n", failure.getClass().getName());
        }
    }

    private static void summarizeAfterExit() {
        Recording r;
        synchronized (DiscoveryJfrProfiler.class) {
            r = recording;
        }
        if (r == null) return;
        try {
            if ("RUNNING".equals(r.getState().name())) r.stop();
            Path dir = FMLPaths.GAMEDIR.get().resolve(".bootoptim").resolve("profiles");
            Files.createDirectories(dir);
            Path file = dir.resolve("discovery-agent106.jfr");
            r.dump(file);
            summarize(file);
            System.out.printf("BOOTOPTIM_DISCOVERY_JFR state=summarized file=%s%n", file.getFileName());
        } catch (Throwable failure) {
            System.out.printf("BOOTOPTIM_DISCOVERY_JFR state=failed action=summarize type=%s%n", failure.getClass().getName());
        } finally {
            try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static void summarize(Path file) throws Exception {
        Map<String, IoStats> io = new HashMap<>();
        Map<String, Integer> samples = new HashMap<>();
        long parksNs = 0L;
        long monitorsNs = 0L;

        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            String name = event.getEventType().getName();
            if ("jdk.FileRead".equals(name) || "jdk.FileWrite".equals(name)) {
                String path = safeString(event, "path");
                String artifact = basename(path);
                IoStats stats = io.computeIfAbsent(artifact, ignored -> new IoStats());
                stats.events++;
                stats.durationNs += event.getDuration().toNanos();
                stats.bytes += safeLong(event, "bytesRead") + safeLong(event, "bytesWritten");
                String stack = stack(event);
                if (stack.contains("jdk.nio.zipfs") || stack.contains("java.util.zip.ZipFile")) stats.zipEvents++;
                if (stack.contains("ModFileParser")) stats.metadataEvents++;
                if (stack.contains("JarSelector") || stack.contains("JarInJarDependencyLocator")) stats.dependencyEvents++;
            } else if ("jdk.ExecutionSample".equals(name)) {
                samples.merge(classify(stack(event)), 1, Integer::sum);
            } else if ("jdk.ThreadPark".equals(name)) {
                parksNs += event.getDuration().toNanos();
            } else if ("jdk.JavaMonitorEnter".equals(name)) {
                monitorsNs += event.getDuration().toNanos();
            }
        }

        io.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, IoStats>>comparingLong(e -> e.getValue().durationNs).reversed())
                .limit(30)
                .forEach(entry -> {
                    IoStats s = entry.getValue();
                    System.out.printf(Locale.ROOT,
                            "BOOTOPTIM_DISCOVERY_IO artifact=%s io_ms=%.3f bytes=%d events=%d zip_events=%d metadata_events=%d dependency_events=%d%n",
                            sanitize(entry.getKey()), s.durationNs / 1_000_000.0, s.bytes, s.events, s.zipEvents, s.metadataEvents, s.dependencyEvents);
                });

        samples.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> System.out.printf("BOOTOPTIM_DISCOVERY_SAMPLE category=%s samples=%d%n", entry.getKey(), entry.getValue()));
        System.out.printf(Locale.ROOT, "BOOTOPTIM_DISCOVERY_BLOCKING parks_ms=%.3f monitors_ms=%.3f%n",
                parksNs / 1_000_000.0, monitorsNs / 1_000_000.0);
    }

    private static String classify(String stack) {
        if (stack.contains("ModFileParser")) return "metadata_parse";
        if (stack.contains("JarSelector") || stack.contains("JarInJarDependencyLocator")) return "dependency_selection";
        if (stack.contains("jdk.nio.zipfs") || stack.contains("java.util.zip.ZipFile")) return "zipfs_zip";
        if (stack.contains("SecureJar") || stack.contains("JarContents")) return "securejar_contents";
        if (stack.contains("connector") || stack.contains("Connector")) return "connector_callback";
        if (stack.contains("IDependencyLocator") || stack.contains("moddiscovery.locators")) return "locator_callback";
        if (stack.contains("ModFileScanData") || stack.contains("ModFile.compileContent")) return "class_scan";
        return "other";
    }

    private static String stack(RecordedEvent event) {
        if (event.getStackTrace() == null) return "";
        List<String> frames = new ArrayList<>();
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            try {
                frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
            } catch (Throwable ignored) {}
        }
        return String.join(";", frames);
    }

    private static String safeString(RecordedEvent event, String field) {
        try { return event.getString(field); } catch (Throwable ignored) { return "unknown"; }
    }

    private static long safeLong(RecordedEvent event, String field) {
        try { return Math.max(0L, event.getLong(field)); } catch (Throwable ignored) { return 0L; }
    }

    private static String basename(String path) {
        if (path == null || path.isBlank()) return "unknown";
        try {
            Path p = Path.of(path);
            Path n = p.getFileName();
            return n == null ? path : n.toString();
        } catch (Throwable ignored) {
            return path;
        }
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replace(' ', '_').replace('\t', '_').replace('\n', '_').replace('\r', '_');
    }

    private static final class IoStats {
        long durationNs;
        long bytes;
        int events;
        int zipEvents;
        int metadataEvents;
        int dependencyEvents;
    }
}
