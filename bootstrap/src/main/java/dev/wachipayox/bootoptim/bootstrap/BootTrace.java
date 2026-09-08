package dev.wachipayox.bootoptim.bootstrap;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level structured startup trace used by the early ModLauncher layer.
 *
 * <p>The trace is deliberately opt-in. OFF has no file-system work and the event fast path is a
 * single mode check. PROFILE and DEVELOPMENT append JSONL events immediately; BENCHMARK keeps a
 * bounded in-memory batch until {@link #flush()} so instrumentation does not turn every marker
 * into a disk write during a timed run.</p>
 *
 * <p>The regular mod contains a source-compatible writer with the same schema. The two modules do
 * not depend on each other, which keeps the bootstrap layer safe when it is loaded before NeoForge
 * has discovered the regular mod.</p>
 */
public final class BootTrace {
    public static final String MODE_PROPERTY = "boot_optim.trace.mode";
    public static final String PATH_PROPERTY = "boot_optim.trace.path";
    public static final String MAX_EVENTS_PROPERTY = "boot_optim.trace.maxEvents";
    public static final String DEFAULT_FILE_NAME = "bootoptim-trace.jsonl";
    private static final int DEFAULT_MAX_EVENTS = 8192;
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long TRACE_ORIGIN_NANO = System.nanoTime();
    private static final long TRACE_ORIGIN_UPTIME_MS = ManagementFactory.getRuntimeMXBean().getUptime();
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static volatile Configuration configuration = Configuration.disabled();
    private static long dropped;

    private BootTrace() {
    }

    /** Configures the writer after ModLauncher has resolved the authoritative game directory. */
    public static void configure(Path gameDirectory) {
        Path resolvedGameDirectory = gameDirectory == null
                ? Path.of(System.getProperty("user.dir", "."))
                : gameDirectory;
        String rawMode = System.getProperty(MODE_PROPERTY, "off");
        Mode mode = Mode.parse(rawMode);
        String configuredPath = System.getProperty(PATH_PROPERTY);
        Path path = configuredPath == null || configuredPath.isBlank()
                ? resolvedGameDirectory.resolve("logs").resolve(DEFAULT_FILE_NAME)
                : Path.of(configuredPath);
        int maxEvents = parseMaxEvents(System.getProperty(MAX_EVENTS_PROPERTY));

        synchronized (LOCK) {
            BUFFER.clear();
            dropped = 0L;
            configuration = new Configuration(mode, path.toAbsolutePath().normalize(), maxEvents);
        }
    }

    public static boolean isEnabled() {
        return configuration.mode() != Mode.OFF;
    }

    public static boolean isBenchmarkBuffered() {
        return configuration.mode() == Mode.BENCHMARK;
    }

    public static void phaseBegin(String phase, String detail) {
        event("phase_begin", phase, null, null, null, null, detail);
    }

    public static void phaseEnd(String phase, String detail) {
        event("phase_end", phase, null, null, null, null, detail);
    }

    public static void milestone(String phase, String detail) {
        event("milestone", phase, null, null, null, null, detail);
    }

    public static void error(String phase, String detail) {
        event("error", phase, null, null, null, null, detail);
    }

    public static void event(
            String kind,
            String phase,
            String task,
            String parent,
            String mod,
            String resource,
            String detail) {
        Configuration current = configuration;
        if (current.mode() == Mode.OFF) {
            return;
        }

        String line = jsonLine(kind, phase, task, parent, mod, resource, detail);
        if (current.mode() == Mode.BENCHMARK) {
            synchronized (LOCK) {
                if (BUFFER.size() >= current.maxEvents()) {
                    dropped++;
                } else {
                    BUFFER.addLast(line);
                }
            }
            return;
        }

        writeLine(current.path(), line);
    }

    /** Flushes a benchmark buffer or is a no-op for streaming modes. */
    public static void flush() {
        Configuration current = configuration;
        if (current.mode() != Mode.BENCHMARK) {
            return;
        }

        synchronized (LOCK) {
            try {
                if (BUFFER.isEmpty() && dropped == 0L) {
                    return;
                }
                StringBuilder output = new StringBuilder(BUFFER.size() * 160);
                while (!BUFFER.isEmpty()) {
                    output.append(BUFFER.removeFirst()).append(System.lineSeparator());
                }
                if (dropped > 0L) {
                    output.append(jsonLine("trace_drop", "trace", null, null, null, null,
                            "dropped_events=" + dropped)).append(System.lineSeparator());
                    dropped = 0L;
                }
                Files.createDirectories(current.path().toAbsolutePath().normalize().getParent());
                Files.writeString(
                        current.path(),
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Diagnostics must never prevent startup. Dropping a trace is preferable to a crash.
                BUFFER.clear();
                dropped = 0L;
            }
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            BUFFER.clear();
            dropped = 0L;
            configuration = Configuration.disabled();
        }
    }

    private static void writeLine(Path path, String line) {
        try {
            Path resolved = path.toAbsolutePath().normalize();
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    resolved,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // Trace is strictly fail-open; the game remains authoritative over diagnostics.
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
        StringBuilder json = new StringBuilder(256);
        json.append('{')
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

    private static int parseMaxEvents(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_EVENTS;
        }
        try {
            return Math.max(64, Math.min(1_000_000, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_EVENTS;
        }
    }

    enum Mode {
        OFF,
        BENCHMARK,
        PROFILE,
        DEVELOPMENT;

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

    private record Configuration(Mode mode, Path path, int maxEvents) {
        static Configuration disabled() {
            return new Configuration(Mode.OFF, Path.of("."), DEFAULT_MAX_EVENTS);
        }
    }
}
