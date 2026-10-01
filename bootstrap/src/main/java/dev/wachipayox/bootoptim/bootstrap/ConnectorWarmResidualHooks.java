package dev.wachipayox.bootoptim.bootstrap;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only observational hooks embedded as one class into the exact Connector jar. */
public final class ConnectorWarmResidualHooks {
    public static final String ENABLE_PROPERTY = "boot_optim.profileConnectorWarm";
    public static final String PATH_PROPERTY = "boot_optim.connectorTrace.path";
    public static final int SCHEMA_VERSION = 1;
    public static final String CONNECTOR_VERSION = "2.0.0-beta.17+1.21.1";
    public static final String CONNECTOR_COMMIT = "8b27f1ad042aae8037bcc522b321c03fcce1a12a";

    private static final boolean ENABLED = Boolean.getBoolean(ENABLE_PROPERTY);
    private static final long ORIGIN_NANO = System.nanoTime();
    private static final long ORIGIN_EPOCH_MS = System.currentTimeMillis();
    private static final long JVM_START_EPOCH_MS = runtimeStartTime();
    private static final long PID = currentPid();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    // Start row: id,parent,predecessor,phase,resource,threadId,threadName,startNano.
    private static final ConcurrentHashMap<Long, Object[]> STARTS = new ConcurrentHashMap<>();
    // Event row: id,parent,predecessor,phase,resource,threadId,threadName,startNano,endNano.
    private static final ConcurrentLinkedQueue<Object[]> EVENTS = new ConcurrentLinkedQueue<>();
    // Cache row: parent,kind,hit,resource,threadId,threadName,atNano.
    private static final ConcurrentLinkedQueue<Object[]> CACHE_EVENTS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<ArrayDeque<Long>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Long> LAST_END = ThreadLocal.withInitial(() -> 0L);
    private static final AtomicLong ERRORS = new AtomicLong();

    static {
        if (ENABLED) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(ConnectorWarmResidualHooks::dump,
                        "BootOptim-Connector-Warm-Trace"));
            } catch (Throwable ignored) {
                ERRORS.incrementAndGet();
            }
        }
    }

    public static long begin(String phase, Object resource) {
        if (!ENABLED) return 0L;
        try {
            Thread thread = Thread.currentThread();
            ArrayDeque<Long> stack = STACK.get();
            long id = NEXT_ID.incrementAndGet();
            long parent = stack.isEmpty() ? 0L : stack.peekLast();
            long predecessor = LAST_END.get();
            STARTS.put(id, new Object[] { id, parent, predecessor, phase, stringify(resource), thread.threadId(),
                    thread.getName(), System.nanoTime() });
            stack.addLast(id);
            return id;
        } catch (Throwable ignored) {
            ERRORS.incrementAndGet();
            return 0L;
        }
    }

    public static void beginScope(String phase, Object resource) {
        begin(phase, resource);
    }

    public static void end(long id) {
        if (!ENABLED || id == 0L) return;
        try {
            long end = System.nanoTime();
            Object[] start = STARTS.remove(id);
            if (start == null) {
                ERRORS.incrementAndGet();
                return;
            }
            ArrayDeque<Long> stack = STACK.get();
            if (stack.isEmpty() || stack.peekLast() != id) {
                ERRORS.incrementAndGet();
                stack.remove(id);
            } else {
                stack.removeLast();
            }
            EVENTS.add(new Object[] { start[0], start[1], start[2], start[3], start[4], start[5], start[6], start[7], end });
            LAST_END.set(id);
        } catch (Throwable ignored) {
            ERRORS.incrementAndGet();
        }
    }

    public static void endScope() {
        if (!ENABLED) return;
        try {
            ArrayDeque<Long> stack = STACK.get();
            if (!stack.isEmpty()) end(stack.peekLast());
        } catch (Throwable ignored) {
            ERRORS.incrementAndGet();
        }
    }

    public static void cacheResult(boolean hit, Object resource, String cacheKind) {
        if (!ENABLED) return;
        try {
            Thread thread = Thread.currentThread();
            ArrayDeque<Long> stack = STACK.get();
            long parent = stack.isEmpty() ? 0L : stack.peekLast();
            CACHE_EVENTS.add(new Object[] { parent, cacheKind, hit, stringify(resource), thread.threadId(),
                    thread.getName(), System.nanoTime() });
        } catch (Throwable ignored) {
            ERRORS.incrementAndGet();
        }
    }

    private static void dump() {
        String raw = System.getProperty(PATH_PROPERTY, "").trim();
        if (raw.isEmpty()) return;
        try {
            Path output = Path.of(raw).toAbsolutePath().normalize();
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = output.resolveSibling(output.getFileName() + ".tmp-" + PID);
            List<Object[]> events = new ArrayList<>(EVENTS);
            events.sort(Comparator.comparingLong(e -> asLong(e[7])));
            List<Object[]> cacheEvents = new ArrayList<>(CACHE_EVENTS);
            cacheEvents.sort(Comparator.comparingLong(e -> asLong(e[6])));
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write("{\"record\":\"connector_trace_header\",\"schema_version\":" + SCHEMA_VERSION
                        + ",\"connector_version\":" + quote(CONNECTOR_VERSION)
                        + ",\"connector_commit\":" + quote(CONNECTOR_COMMIT)
                        + ",\"pid\":" + PID
                        + ",\"jvm_start_epoch_ms\":" + JVM_START_EPOCH_MS
                        + ",\"trace_origin_epoch_ms\":" + ORIGIN_EPOCH_MS
                        + ",\"trace_origin_mono_ns\":" + ORIGIN_NANO
                        + ",\"clock_source\":\"System.nanoTime\"}");
                writer.newLine();
                for (Object[] event : events) {
                    long start = asLong(event[7]);
                    long end = asLong(event[8]);
                    writer.write("{\"record\":\"scope\",\"id\":" + event[0]
                            + ",\"parent_id\":" + event[1]
                            + ",\"predecessor_id\":" + event[2]
                            + ",\"phase\":" + quote((String) event[3])
                            + ",\"resource\":" + quote((String) event[4])
                            + ",\"thread_id\":" + event[5]
                            + ",\"thread\":" + quote((String) event[6])
                            + ",\"start_mono_ns\":" + start
                            + ",\"end_mono_ns\":" + end
                            + ",\"duration_ns\":" + Math.max(0L, end - start) + "}");
                    writer.newLine();
                }
                for (Object[] event : cacheEvents) {
                    writer.write("{\"record\":\"cache\",\"parent_id\":" + event[0]
                            + ",\"kind\":" + quote((String) event[1])
                            + ",\"hit\":" + event[2]
                            + ",\"resource\":" + quote((String) event[3])
                            + ",\"thread_id\":" + event[4]
                            + ",\"thread\":" + quote((String) event[5])
                            + ",\"at_mono_ns\":" + event[6] + "}");
                    writer.newLine();
                }
                writer.write("{\"record\":\"connector_trace_summary\",\"scope_events\":" + events.size()
                        + ",\"cache_events\":" + cacheEvents.size()
                        + ",\"unfinished_scopes\":" + STARTS.size()
                        + ",\"trace_errors\":" + ERRORS.get() + "}");
                writer.newLine();
            }
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // Trace I/O is never a startup dependency.
        }
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static String stringify(Object value) {
        try { return value == null ? null : String.valueOf(value); }
        catch (Throwable ignored) { return "<unprintable>"; }
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static long currentPid() {
        try { return ProcessHandle.current().pid(); }
        catch (Throwable ignored) { return -1L; }
    }

    private static long runtimeStartTime() {
        try { return ManagementFactory.getRuntimeMXBean().getStartTime(); }
        catch (Throwable ignored) { return -1L; }
    }

    private ConnectorWarmResidualHooks() {}
}
