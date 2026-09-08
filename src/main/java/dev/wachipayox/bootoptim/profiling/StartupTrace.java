package dev.wachipayox.bootoptim.profiling;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Game-layer half of BootOptim's opt-in structured startup trace.
 *
 * <p>This intentionally mirrors the bootstrap writer without depending on the bootstrap module:
 * the bootstrap JAR is loaded by ModLauncher before the regular mod and must remain optional from
 * the regular mod's compile-time perspective.</p>
 */
public final class StartupTrace {
    public static final String MODE_PROPERTY = "boot_optim.trace.mode";
    public static final String PATH_PROPERTY = "boot_optim.trace.path";
    public static final String MAX_EVENTS_PROPERTY = "boot_optim.trace.maxEvents";
    private static final int DEFAULT_MAX_EVENTS = 8192;
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long TRACE_ORIGIN_NANO = System.nanoTime();
    private static final long TRACE_ORIGIN_UPTIME_MS = ManagementFactory.getRuntimeMXBean().getUptime();
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static volatile Mode mode = Mode.parse(System.getProperty(MODE_PROPERTY, "off"));
    private static long dropped;

    private StartupTrace() {
    }

    public static boolean isEnabled() {
        return mode != Mode.OFF;
    }

    public static void phaseBegin(String phase, String detail) {
        event("phase_begin", phase, detail);
    }

    public static void phaseEnd(String phase, String detail) {
        event("phase_end", phase, detail);
    }

    public static void milestone(String phase, String detail) {
        event("milestone", phase, detail);
    }

    public static void error(String phase, String detail) {
        event("error", phase, detail);
    }

    public static void event(String kind, String phase, String detail) {
        event(kind, phase, null, null, null, null, detail);
    }

    public static void event(
            String kind,
            String phase,
            String task,
            String parent,
            String mod,
            String resource,
            String detail) {
        Mode current = mode;
        if (current == Mode.OFF) {
            return;
        }
        String line = jsonLine(kind, phase, task, parent, mod, resource, detail);
        if (current == Mode.BENCHMARK) {
            synchronized (LOCK) {
                if (BUFFER.size() >= maxEvents()) {
                    dropped++;
                } else {
                    BUFFER.addLast(line);
                }
            }
        } else {
            writeLine(line);
        }
    }

    public static void flush() {
        if (mode != Mode.BENCHMARK) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (BUFFER.isEmpty() && dropped == 0L) {
                    return;
                }
                Path path = path();
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder output = new StringBuilder(BUFFER.size() * 160);
                while (!BUFFER.isEmpty()) {
                    output.append(BUFFER.removeFirst()).append(System.lineSeparator());
                }
                if (dropped > 0L) {
                    output.append(jsonLine("trace_drop", "trace", null, null, null, null,
                                    "dropped_events=" + dropped))
                            .append(System.lineSeparator());
                    dropped = 0L;
                }
                Files.writeString(path, output, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                BUFFER.clear();
                dropped = 0L;
            }
        }
    }

    private static int maxEvents() {
        try {
            return Math.max(64, Math.min(1_000_000,
                    Integer.parseInt(System.getProperty(MAX_EVENTS_PROPERTY, "" + DEFAULT_MAX_EVENTS))));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_EVENTS;
        }
    }

    private static Path path() {
        String explicit = System.getProperty(PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String report = System.getProperty("boot_optim.startupLog.path");
        if (report != null && !report.isBlank()) {
            Path reportPath = Path.of(report).toAbsolutePath().normalize();
            Path parent = reportPath.getParent();
            if (parent != null) {
                return parent.resolve("bootoptim-trace.jsonl");
            }
        }
        return Path.of("logs").resolve("bootoptim-trace.jsonl").toAbsolutePath().normalize();
    }

    private static void writeLine(String line) {
        try {
            Path path = path();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // Diagnostics must never alter the game's behavior.
        }
    }

    private static String jsonLine(
            String kind,
            String phase,
            String task,
            String parent,
            String mod,
            String resource,
            String detail) {
        long monotonic = System.nanoTime();
        long uptimeNs = TRACE_ORIGIN_UPTIME_MS * 1_000_000L + (monotonic - TRACE_ORIGIN_NANO);
        StringBuilder json = new StringBuilder(224)
                .append('{')
                .append("\"schema\":1")
                .append(",\"sequence\":").append(SEQUENCE.incrementAndGet())
                .append(",\"uptime_ns\":").append(uptimeNs)
                .append(",\"monotonic_ns\":").append(monotonic)
                .append(",\"thread_id\":").append(Thread.currentThread().getId())
                .append(",\"thread\":").append(quote(Thread.currentThread().getName()))
                .append(",\"kind\":").append(quote(kind));
        appendOptional(json, "phase", phase);
        appendOptional(json, "task", task);
        appendOptional(json, "parent", parent);
        appendOptional(json, "mod", mod);
        appendOptional(json, "resource", resource);
        appendOptional(json, "detail", detail);
        return json.append('}').toString();
    }

    private static void appendOptional(StringBuilder json, String name, String value) {
        if (value != null && !value.isBlank()) {
            json.append(',').append(quote(name)).append(':').append(quote(value));
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    enum Mode {
        OFF, BENCHMARK, PROFILE, DEVELOPMENT;

        static Mode parse(String raw) {
            if (raw == null) {
                return OFF;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "benchmark", "bench" -> BENCHMARK;
                case "profile", "profiling" -> PROFILE;
                case "development", "dev" -> DEVELOPMENT;
                default -> OFF;
            };
        }
    }
}
