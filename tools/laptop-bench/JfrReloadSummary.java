import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Read-only JFR attribution for explicit JVM-epoch-millisecond windows. */
public final class JfrReloadSummary {
    private record Window(String name, long start, long end) {}
    private static final class Counts {
        long count;
        double milliseconds;
        double maxMilliseconds;
        final Map<String, Double> paths = new HashMap<>();
        final Map<String, Double> classes = new HashMap<>();
        final Map<String, Double> frames = new HashMap<>();
        final Map<String, Double> families = new HashMap<>();
        void add(RecordedEvent event, String type, double windowMilliseconds) {
            count++;
            double ms = event.hasField("duration") ? windowMilliseconds : 0;
            milliseconds += ms;
            maxMilliseconds = Math.max(maxMilliseconds, ms);
            double weight = type.equals("jdk.FileRead") ? ms :
                    type.equals("jdk.ObjectAllocationSample") && event.hasField("weight") ? event.getLong("weight") : 1;
            if (type.equals("jdk.FileRead") && event.hasField("path")) {
                String path = event.getString("path");
                paths.merge(path == null ? "<unknown>" : path, ms, Double::sum);
            }
            if (type.equals("jdk.ObjectAllocationSample") && event.hasField("objectClass")) {
                String allocated = event.getClass("objectClass").getName();
                classes.merge(allocated, weight, Double::sum);
            }
            RecordedStackTrace trace = event.getStackTrace();
            if (trace != null) {
                if (type.equals("jdk.FileRead")) {
                    String family = "other";
                    for (RecordedFrame frame : trace.getFrames()) {
                        String method = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                        if (method.contains("ModelManager.lambda$loadBlockModels")) { family = "model_input"; break; }
                        if (method.contains("ModelManager.lambda$loadBlockStates")) { family = "blockstate_input"; break; }
                        if (method.contains("TextureUtil.readResource")) { family = "texture_input"; break; }
                    }
                    families.merge(family, ms, Double::sum);
                }
                for (RecordedFrame frame : trace.getFrames()) {
                    String owner = frame.getMethod().getType().getName();
                    if (owner.startsWith("java.") || owner.startsWith("jdk.") ||
                        owner.startsWith("sun.") || owner.startsWith("com.sun.")) continue;
                    frames.merge(owner + "." + frame.getMethod().getName(), weight, Double::sum);
                    break;
                }
            }
        }
    }
    private static void top(String label, Map<String, Double> values, int limit) {
        values.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .forEach(e -> System.out.printf("  %s %.3f %s%n", label, e.getValue(), e.getKey()));
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 2) throw new IllegalArgumentException("recording.jfr label:startEpochMs:endEpochMs ...");
        List<Window> windows = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String[] parts = args[i].split(":", 3);
            if (parts.length != 3) throw new IllegalArgumentException(args[i]);
            Window window = new Window(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
            if (window.end <= window.start) throw new IllegalArgumentException(args[i]);
            windows.add(window);
        }
        Map<Window, Map<String, Counts>> data = new HashMap<>();
        for (Window window : windows) data.put(window, new HashMap<>());
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!type.equals("jdk.FileRead") && !type.equals("jdk.GarbageCollection") &&
                    !type.equals("jdk.GCPhasePause") && !type.equals("jdk.ExecutionSample") &&
                    !type.equals("jdk.ObjectAllocationSample") && !type.equals("jdk.ThreadPark") &&
                    !type.equals("jdk.JavaMonitorWait")) continue;
                long when = event.getStartTime().toEpochMilli();
                long until = event.getEndTime().toEpochMilli();
                for (Window window : windows) {
                    boolean durationEvent = event.hasField("duration") && until > when;
                    if ((durationEvent && when < window.end && until > window.start) ||
                        (!durationEvent && when >= window.start && when < window.end)) {
                        long intersection = durationEvent ? Math.max(0,
                                Math.min(until, window.end) - Math.max(when, window.start)) : 0;
                        data.get(window).computeIfAbsent(type, ignored -> new Counts()).add(event, type, intersection);
                    }
                }
            }
        }
        for (Window window : windows) {
            System.out.printf("WINDOW %s %.3f s%n", window.name, (window.end - window.start) / 1000.0);
            Map<String, Counts> types = data.get(window);
            for (String type : List.of("jdk.FileRead", "jdk.GarbageCollection", "jdk.GCPhasePause",
                    "jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.ThreadPark", "jdk.JavaMonitorWait")) {
                Counts counts = types.getOrDefault(type, new Counts());
                System.out.printf("EVENT %s count=%d duration_ms_sum=%.3f max_ms=%.3f%n",
                        type, counts.count, counts.milliseconds, counts.maxMilliseconds);
                if (type.equals("jdk.FileRead")) top("PATH_MS", counts.paths, 12);
                if (type.equals("jdk.FileRead")) top("FAMILY_MS", counts.families, 12);
                if (type.equals("jdk.FileRead") || type.equals("jdk.ExecutionSample")) top("FRAME_WEIGHT", counts.frames, 12);
                if (type.equals("jdk.ObjectAllocationSample")) top("CLASS_BYTES", counts.classes, 12);
                if (type.equals("jdk.ObjectAllocationSample")) top("FRAME_BYTES", counts.frames, 12);
            }
        }
    }
}
