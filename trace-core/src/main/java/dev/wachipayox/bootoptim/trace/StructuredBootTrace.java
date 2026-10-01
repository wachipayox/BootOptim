package dev.wachipayox.bootoptim.trace;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** JDK-only, versioned and fail-open structured boot tracing core. */
public final class StructuredBootTrace implements AutoCloseable {
    public static final String MODE_PROPERTY = "boot_optim.bootTrace.mode";
    public static final String PATH_PROPERTY = "boot_optim.bootTrace.path";
    public static final String CAPACITY_PROPERTY = "boot_optim.bootTrace.capacity";
    public static final String ORIGIN_PROPERTY = "boot_optim.bootTrace.origin";
    public static final String ENDPOINT_PROPERTY = "boot_optim.bootTrace.endpoint";
    public static final String DEVELOPMENT_ENDPOINT_PROPERTY = "boot_optim.bootTrace.developmentEndpoint";
    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_CAPACITY = 131_072;

    public enum Mode {
        OFF, BENCHMARK, PROFILE, DEVELOPMENT;

        static Mode parse(String value) {
            if (value == null || value.isBlank()) return OFF;
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return OFF;
            }
        }
    }

    public enum EventType {
        PHASE_BEGIN("phase_begin"), PHASE_END("phase_end"),
        TASK_BEGIN("task_begin"), TASK_END("task_end"),
        BARRIER_WAIT("barrier_wait"), BARRIER_OPEN("barrier_open"),
        COMMIT_BEGIN("commit_begin"), COMMIT_END("commit_end"),
        BLOCKED_ON("blocked_on"), MOD_CALLBACK("mod_callback"),
        RESOURCE_OPEN("resource_open"), RESOURCE_PARSE("resource_parse"),
        FALLBACK("fallback"), ERROR("error");

        private final String wireName;
        EventType(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
    }

    public record Config(Mode mode, Path outputPath, int capacity, String measurementOrigin,
                         String endpoint, String developmentEndpoint) {
        public Config {
            mode = mode == null ? Mode.OFF : mode;
            if (capacity < 1) capacity = DEFAULT_CAPACITY;
            measurementOrigin = normalize(measurementOrigin, "unspecified");
            endpoint = normalize(endpoint, "unspecified");
            developmentEndpoint = normalize(developmentEndpoint, "none");
        }
    }

    /** Adapter point for future development-only named-pipe/local-socket tooling. */
    public interface DevelopmentSink extends AutoCloseable {
        void accept(String jsonLine) throws Exception;
        @Override default void close() throws Exception {}
    }

    private static final StructuredBootTrace GLOBAL = fromSystemProperties();

    private final Config config;
    private final AtomicReferenceArray<Event> events;
    private final LongAdder[] counters;
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong nextTaskId = new AtomicLong();
    private final LongAdder droppedEvents = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();
    private final LongAdder developmentSinkFailures = new LongAdder();
    private final AtomicReference<DevelopmentSink> developmentSink = new AtomicReference<>();
    private final AtomicLong developmentReplayMaxSequence = new AtomicLong(-1L);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object flushLock = new Object();
    private final Object developmentLock = new Object();
    private final LongSupplier nanoClock;
    private final long traceOriginNano;
    private final long traceOriginEpochMs;
    private final long jvmStartEpochMs;
    private final long pid;
    private final String jvmId;

    private StructuredBootTrace(Config config, LongSupplier nanoClock, long traceOriginNano,
            long traceOriginEpochMs, long jvmStartEpochMs, long pid, String jvmId,
            boolean registerShutdownHook) {
        this.config = config;
        this.nanoClock = nanoClock;
        this.traceOriginNano = traceOriginNano;
        this.traceOriginEpochMs = traceOriginEpochMs;
        this.jvmStartEpochMs = jvmStartEpochMs;
        this.pid = pid;
        this.jvmId = jvmId;
        this.events = new AtomicReferenceArray<>(isDetailedMode(config.mode()) ? config.capacity() : 0);
        this.counters = new LongAdder[EventType.values().length];
        for (int i = 0; i < counters.length; i++) counters[i] = new LongAdder();
        if (registerShutdownHook && config.mode() != Mode.OFF) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(this::close, "BootOptim-BootTrace-Flush"));
            } catch (Throwable ignored) {
                // Never make tracing a startup dependency.
            }
        }
    }

    public static StructuredBootTrace global() { return GLOBAL; }

    public static StructuredBootTrace create(Config config) {
        if (config == null || config.mode() == Mode.OFF) return offInstance();
        LongSupplier clock = System::nanoTime;
        long originNano = clock.getAsLong();
        long start = runtimeStartTime();
        long pid = currentPid();
        return new StructuredBootTrace(config, clock, originNano, System.currentTimeMillis(), start,
                pid, makeJvmId(pid, start, originNano), true);
    }

    static StructuredBootTrace createForTest(Config config, LongSupplier nanoClock, long traceOriginNano,
            long traceOriginEpochMs, long jvmStartEpochMs, long pid, String jvmId) {
        return new StructuredBootTrace(config, nanoClock, traceOriginNano, traceOriginEpochMs,
                jvmStartEpochMs, pid, jvmId, false);
    }

    public Mode mode() { return config.mode(); }
    public boolean isEnabled() { return config.mode() != Mode.OFF; }
    public boolean isDetailed() { return isDetailedMode(config.mode()); }
    public long nextTaskId() { return isEnabled() ? nextTaskId.incrementAndGet() : 0L; }
    public long count(EventType type) { return counters[type.ordinal()].sum(); }
    public long droppedEventCount() { return droppedEvents.sum(); }
    public long bufferedEventCount() { return isDetailed() ? Math.min(nextSequence.get(), events.length()) : 0L; }

    public long beginTask(String phase, long parentTaskId, long[] dependencyIds,
            String modId, String resourceId, long reloadGeneration) {
        long taskId = nextTaskId();
        record(EventType.TASK_BEGIN, taskId, parentTaskId, dependencyIds, phase,
                -1L, modId, resourceId, reloadGeneration, null);
        return taskId;
    }

    public void endTask(long taskId, String phase, long cpuNanos, String detail) {
        record(EventType.TASK_END, taskId, 0L, null, phase, cpuNanos, null, null, -1L, detail);
    }

    /** Records one schema event. BENCHMARK does no nanoTime/thread capture/allocation/serialization/I/O. */
    public void record(EventType type, long taskId, long parentTaskId, long[] dependencyIds,
            String phase, long cpuNanos, String modId, String resourceId,
            long reloadGeneration, String detail) {
        if (type == null || closed.get() || config.mode() == Mode.OFF) return;
        counters[type.ordinal()].increment();
        if (config.mode() == Mode.BENCHMARK) return;

        long sequence = nextSequence.getAndIncrement();
        if (sequence >= events.length()) {
            droppedEvents.increment();
            return;
        }
        Thread thread = Thread.currentThread();
        Event event = new Event(sequence, type, taskId, parentTaskId,
                dependencyIds == null ? new long[0] : dependencyIds.clone(), phase,
                thread.threadId(), thread.getName(), Math.max(0L, nanoClock.getAsLong() - traceOriginNano),
                cpuNanos, modId, resourceId, reloadGeneration, detail);
        events.set((int) sequence, event);
        if (config.mode() == Mode.DEVELOPMENT) streamDevelopment(event);
    }

    public void record(EventType type, long taskId, String phase) {
        record(type, taskId, 0L, null, phase, -1L, null, null, -1L, null);
    }

    /** Installs tooling transport explicitly; BootOptim never opens IPC on its own. */
    public boolean installDevelopmentSink(DevelopmentSink sink) {
        if (sink == null || config.mode() != Mode.DEVELOPMENT || closed.get()) return false;
        synchronized (developmentLock) {
            if (!developmentSink.compareAndSet(null, sink)) return false;
            try {
                List<Event> replay = snapshotEvents();
                sink.accept(headerJson(snapshotMissingSlots()));
                for (Event event : replay) sink.accept(eventJson(event));
                if (!replay.isEmpty()) developmentReplayMaxSequence.set(replay.get(replay.size() - 1).sequence);
                return true;
            } catch (Throwable ignored) {
                disableDevelopmentSinkLocked(sink);
                return false;
            }
        }
    }

    /** Writes/replaces a local JSONL snapshot. Failures are swallowed and counted. */
    public void flush() {
        if (config.mode() == Mode.OFF || config.outputPath() == null) return;
        synchronized (flushLock) {
            try {
                Path output = config.outputPath().toAbsolutePath().normalize();
                Path parent = output.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temp = output.resolveSibling(output.getFileName() + ".tmp-" + pid);
                int missingSlots = snapshotMissingSlots();
                List<Event> snapshot = snapshotEvents();
                try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    writer.write(headerJson(missingSlots)); writer.newLine();
                    for (Event event : snapshot) { writer.write(eventJson(event)); writer.newLine(); }
                    writer.write(summaryJson(missingSlots, snapshot.size())); writer.newLine();
                }
                try {
                    Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable ignored) {
                flushFailures.increment();
            }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        flush();
        synchronized (developmentLock) {
            DevelopmentSink sink = developmentSink.getAndSet(null);
            if (sink != null) {
                try { sink.accept(summaryJson(snapshotMissingSlots(), snapshotEvents().size())); }
                catch (Throwable ignored) { developmentSinkFailures.increment(); }
                try { sink.close(); }
                catch (Throwable ignored) { developmentSinkFailures.increment(); }
            }
        }
    }

    private static StructuredBootTrace fromSystemProperties() {
        Mode mode = Mode.parse(System.getProperty(MODE_PROPERTY, "off"));
        Config config = new Config(mode, configuredPath(mode), parseCapacity(System.getProperty(CAPACITY_PROPERTY)),
                System.getProperty(ORIGIN_PROPERTY, "unspecified"),
                System.getProperty(ENDPOINT_PROPERTY, "unspecified"),
                System.getProperty(DEVELOPMENT_ENDPOINT_PROPERTY, "none"));
        return mode == Mode.OFF ? offInstance() : create(config);
    }

    private static StructuredBootTrace offInstance() {
        Config off = new Config(Mode.OFF, null, 1, "unspecified", "unspecified", "none");
        return new StructuredBootTrace(off, () -> 0L, 0L, 0L, 0L, -1L, "off", false);
    }

    private static Path configuredPath(Mode mode) {
        if (mode == Mode.OFF) return null;
        String value = System.getProperty(PATH_PROPERTY, "logs/bootoptim-trace.jsonl");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private void streamDevelopment(Event event) {
        synchronized (developmentLock) {
            DevelopmentSink sink = developmentSink.get();
            if (sink == null || event.sequence <= developmentReplayMaxSequence.get()) return;
            try { sink.accept(eventJson(event)); }
            catch (Throwable ignored) { disableDevelopmentSinkLocked(sink); }
        }
    }

    private void disableDevelopmentSinkLocked(DevelopmentSink sink) {
        developmentSinkFailures.increment();
        developmentSink.compareAndSet(sink, null);
        try { sink.close(); }
        catch (Throwable ignored) { developmentSinkFailures.increment(); }
    }

    private List<Event> snapshotEvents() {
        if (!isDetailed()) return List.of();
        int limit = (int) Math.min(nextSequence.get(), events.length());
        List<Event> snapshot = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Event event = events.get(i);
            if (event != null) snapshot.add(event);
        }
        return snapshot;
    }

    private int snapshotMissingSlots() {
        if (!isDetailed()) return 0;
        int limit = (int) Math.min(nextSequence.get(), events.length());
        int missing = 0;
        for (int i = 0; i < limit; i++) if (events.get(i) == null) missing++;
        return missing;
    }

    private String headerJson(int missingSlots) {
        return "{" +
                "\"record\":\"trace_header\",\"schema\":\"bootoptim.boottrace\",\"schema_version\":" + SCHEMA_VERSION + ',' +
                "\"mode\":" + quote(config.mode().name().toLowerCase(Locale.ROOT)) + ',' +
                "\"jvm_id\":" + quote(jvmId) + ",\"pid\":" + pid + ",\"jvm_start_epoch_ms\":" + jvmStartEpochMs + ',' +
                "\"trace_origin_epoch_ms\":" + traceOriginEpochMs + ",\"trace_origin_mono_ns\":" + traceOriginNano + ',' +
                "\"clock_kind\":\"monotonic\",\"clock_source\":\"System.nanoTime\",\"clock_origin\":\"trace_init\"," +
                "\"measurement_origin\":" + quote(config.measurementOrigin()) + ",\"endpoint\":" + quote(config.endpoint()) + ',' +
                "\"development_endpoint\":" + quote(config.developmentEndpoint()) + ",\"dropped_events\":" + (droppedEvents.sum() + missingSlots) + "}";
    }

    private String summaryJson(int missingSlots, int buffered) {
        Map<EventType, Long> counts = new EnumMap<>(EventType.class);
        for (EventType type : EventType.values()) counts.put(type, counters[type.ordinal()].sum());
        StringBuilder counterJson = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<EventType, Long> entry : counts.entrySet()) {
            if (!first) counterJson.append(',');
            first = false;
            counterJson.append(quote(entry.getKey().wireName())).append(':').append(entry.getValue());
        }
        counterJson.append('}');
        return "{" +
                "\"record\":\"trace_summary\",\"schema_version\":" + SCHEMA_VERSION + ",\"jvm_id\":" + quote(jvmId) + ',' +
                "\"buffered_events\":" + buffered + ",\"dropped_events\":" + (droppedEvents.sum() + missingSlots) + ',' +
                "\"flush_failures\":" + flushFailures.sum() + ",\"development_sink_failures\":" + developmentSinkFailures.sum() + ',' +
                "\"event_counters\":" + counterJson + "}";
    }

    private String eventJson(Event event) {
        StringBuilder json = new StringBuilder(320);
        json.append('{').append("\"record\":\"event\",\"v\":").append(SCHEMA_VERSION)
                .append(",\"jvm_id\":").append(quote(jvmId)).append(",\"seq\":").append(event.sequence)
                .append(",\"type\":").append(quote(event.type.wireName())).append(",\"mono_ns\":").append(event.monotonicNanos)
                .append(",\"thread_id\":").append(event.threadId).append(",\"thread\":").append(quote(event.threadName));
        appendOptionalLong(json, "task_id", event.taskId, 0L);
        appendOptionalLong(json, "parent_task_id", event.parentTaskId, 0L);
        if (event.dependencyIds.length > 0) {
            json.append(",\"dependency_ids\":[");
            for (int i = 0; i < event.dependencyIds.length; i++) { if (i > 0) json.append(','); json.append(event.dependencyIds[i]); }
            json.append(']');
        }
        appendOptionalString(json, "phase", event.phase);
        appendOptionalLong(json, "cpu_ns", event.cpuNanos, -1L);
        appendOptionalString(json, "mod", event.modId);
        appendOptionalString(json, "resource", event.resourceId);
        appendOptionalLong(json, "reload_generation", event.reloadGeneration, -1L);
        appendOptionalString(json, "detail", event.detail);
        return json.append('}').toString();
    }

    private static void appendOptionalLong(StringBuilder json, String name, long value, long absent) {
        if (value != absent) json.append(',').append(quote(name)).append(':').append(value);
    }
    private static void appendOptionalString(StringBuilder json, String name, String value) {
        if (value != null) json.append(',').append(quote(name)).append(':').append(quote(value));
    }
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> { if (c < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
    private static int parseCapacity(String value) {
        if (value == null || value.isBlank()) return DEFAULT_CAPACITY;
        try { int parsed = Integer.parseInt(value.trim()); return parsed > 0 ? parsed : DEFAULT_CAPACITY; }
        catch (NumberFormatException ignored) { return DEFAULT_CAPACITY; }
    }
    private static boolean isDetailedMode(Mode mode) { return mode == Mode.PROFILE || mode == Mode.DEVELOPMENT; }
    private static String normalize(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static long currentPid() { try { return ProcessHandle.current().pid(); } catch (Throwable ignored) { return -1L; } }
    private static long runtimeStartTime() { try { return ManagementFactory.getRuntimeMXBean().getStartTime(); } catch (Throwable ignored) { return -1L; } }
    private static String makeJvmId(long pid, long start, long originNano) { return pid + "-" + start + "-" + Long.toUnsignedString(originNano); }

    private static final class Event {
        final long sequence; final EventType type; final long taskId; final long parentTaskId; final long[] dependencyIds;
        final String phase; final long threadId; final String threadName; final long monotonicNanos; final long cpuNanos;
        final String modId; final String resourceId; final long reloadGeneration; final String detail;
        Event(long sequence, EventType type, long taskId, long parentTaskId, long[] dependencyIds, String phase,
                long threadId, String threadName, long monotonicNanos, long cpuNanos, String modId, String resourceId,
                long reloadGeneration, String detail) {
            this.sequence = sequence; this.type = type; this.taskId = taskId; this.parentTaskId = parentTaskId;
            this.dependencyIds = dependencyIds; this.phase = phase; this.threadId = threadId; this.threadName = threadName;
            this.monotonicNanos = monotonicNanos; this.cpuNanos = cpuNanos; this.modId = modId; this.resourceId = resourceId;
            this.reloadGeneration = reloadGeneration; this.detail = detail;
        }
    }
}
