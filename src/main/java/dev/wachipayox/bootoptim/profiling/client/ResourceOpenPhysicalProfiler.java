package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only low-overhead profiler for the resource path below ModelManager.
 *
 * <p>No per-resource logging and no synchronized ranking structure are used. Hot-path
 * accounting is primitive-local or LongAdder-based; sorting happens once after the
 * main-menu marker.</p>
 */
public final class ResourceOpenPhysicalProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENABLE_PROPERTY = "boot_optim.profileResourcePhysicalPath";
    private static final AtomicBoolean DUMPED = new AtomicBoolean();
    private static final ConcurrentHashMap<Key, Stats> STATS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<PackIdentity> PACK_SCOPE = new ThreadLocal<>();

    private ResourceOpenPhysicalProfiler() {
    }

    public static boolean enabled() {
        return StartupProfiler.isEnabled() && Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    public static void enter(String phase, ResourceLocation id, Resource resource) {
        if (!enabled()) return;
        CONTEXT.set(new Context(
                phase,
                id == null ? "unknown" : id.toString(),
                resource == null ? null : new PackIdentity(resource.sourcePackId(), resource.source().getClass().getName())));
    }

    public static void enterEnumeration(String phase) {
        if (!enabled()) return;
        CONTEXT.set(new Context(phase, "enumeration", null));
    }

    public static void setCurrentResource(Resource resource) {
        if (!enabled() || resource == null) return;
        Context context = CONTEXT.get();
        if (context != null) {
            CONTEXT.set(new Context(context.phase(), context.resourceId(),
                    new PackIdentity(resource.sourcePackId(), resource.source().getClass().getName())));
        }
    }

    public static void clearContext() {
        CONTEXT.remove();
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    public static void recordCurrent(String stage, long startedNanos) {
        if (startedNanos == 0L) return;
        Context context = CONTEXT.get();
        PackIdentity pack = context == null ? PACK_SCOPE.get() : context.pack();
        record(stage, context == null ? "outside_model_context" : context.phase(), pack,
                System.nanoTime() - startedNanos, 1L, 0L);
    }

    public static void recordEnumeration(String phase, long startedNanos, long items) {
        if (startedNanos == 0L) return;
        record("enumeration.wall", phase, null, System.nanoTime() - startedNanos, items < 0 ? 0 : items, 0L);
    }

    public static PackToken beginPackScope(String packId, String sourceClass) {
        if (!enabled()) return null;
        Context context = CONTEXT.get();
        if (context == null || context.phase() == null || !context.phase().endsWith(".enumeration")) return null;
        PackIdentity previous = PACK_SCOPE.get();
        PackIdentity current = new PackIdentity(packId, sourceClass);
        PACK_SCOPE.set(current);
        return new PackToken(previous, current, context.phase(), System.nanoTime());
    }

    public static void endPackList(PackToken token) {
        if (token == null) return;
        record("pack.list_resources", token.phase(), token.current(),
                System.nanoTime() - token.startedNanos(), 1L, 0L);
        restorePack(token.previous());
    }

    public static OpenToken beginResourceOpen(String packId, String sourceClass) {
        if (!enabled()) return null;
        Context context = CONTEXT.get();
        if (context == null || !isModelPhase(context.phase())) return null;
        PackIdentity previous = PACK_SCOPE.get();
        PackIdentity current = new PackIdentity(packId, sourceClass);
        PACK_SCOPE.set(current);
        return new OpenToken(context.phase(), previous, current, System.nanoTime());
    }

    public static InputStream endResourceOpen(OpenToken token, InputStream stream) {
        if (token == null) return stream;
        record("resource.open_supplier", token.phase(), token.current(), System.nanoTime() - token.startedNanos(), 1L, 0L);
        restorePack(token.previous());
        if (stream == null) return null;
        return new MeasuredInputStream(stream, token.phase(), token.current());
    }

    public static ReaderToken beginReaderOpen(String packId, String sourceClass) {
        if (!enabled()) return null;
        Context context = CONTEXT.get();
        if (context == null || !isModelPhase(context.phase())) return null;
        return new ReaderToken(context.phase(), new PackIdentity(packId, sourceClass), System.nanoTime());
    }

    public static void endReaderOpen(ReaderToken token) {
        if (token == null) return;
        record("resource.open_as_reader", token.phase(), token.pack(), System.nanoTime() - token.startedNanos(), 1L, 0L);
    }

    public static void dump() {
        if (!enabled() || !DUMPED.compareAndSet(false, true)) return;

        LOGGER.info("BOOTOPTIM_RESOURCE_PHYSICAL kind=summary rows={} note=metric_kind_distinguishes_wall_from_task_sum;critical_path_not_measured", STATS.size());

        STATS.entrySet().stream()
                .sorted(Map.Entry.<Key, Stats>comparingByValue(Comparator.comparingLong(Stats::nanos)).reversed())
                .forEach(entry -> {
                    Key key = entry.getKey();
                    Stats stats = entry.getValue();
                    String metricKind = "enumeration.wall".equals(key.stage()) ? "wall" : "task_sum";
                    LOGGER.info(
                            "BOOTOPTIM_RESOURCE_PHYSICAL stage={} metric_kind={} phase={} pack={} source={} calls={} elapsed_ms={} bytes={} avg_us={}",
                            key.stage(), metricKind, key.phase(), key.packId(), key.sourceClass(), stats.calls(), nanosToMs(stats.nanos()),
                            stats.bytes(), stats.calls() == 0 ? 0L : (stats.nanos() / stats.calls()) / 1_000L);
                });
    }

    private static boolean isModelPhase(String phase) {
        return phase != null && (phase.startsWith("block_models") || phase.startsWith("block_states"));
    }

    private static void restorePack(PackIdentity previous) {
        if (previous == null) PACK_SCOPE.remove(); else PACK_SCOPE.set(previous);
    }

    private static void record(String stage, String phase, PackIdentity pack, long nanos, long calls, long bytes) {
        if (nanos < 0) return;
        String packId = pack == null ? "all" : sanitize(pack.packId());
        String sourceClass = pack == null ? "all" : sanitize(pack.sourceClass());
        Stats stats = STATS.computeIfAbsent(new Key(stage, phase, packId, sourceClass), ignored -> new Stats());
        stats.nanos.add(nanos);
        if (calls > 0) stats.calls.add(calls);
        if (bytes > 0) stats.bytes.add(bytes);
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replace(' ', '_');
    }

    private static String nanosToMs(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record Context(String phase, String resourceId, PackIdentity pack) {
    }

    private record PackIdentity(String packId, String sourceClass) {
    }

    public record PackToken(PackIdentity previous, PackIdentity current, String phase, long startedNanos) {
    }

    public record OpenToken(String phase, PackIdentity previous, PackIdentity current, long startedNanos) {
    }

    public record ReaderToken(String phase, PackIdentity pack, long startedNanos) {
    }

    private record Key(String stage, String phase, String packId, String sourceClass) {
    }

    private static final class Stats {
        private final LongAdder nanos = new LongAdder();
        private final LongAdder calls = new LongAdder();
        private final LongAdder bytes = new LongAdder();

        long nanos() { return nanos.sum(); }
        long calls() { return calls.sum(); }
        long bytes() { return bytes.sum(); }
    }

    private static final class MeasuredInputStream extends FilterInputStream {
        private final String phase;
        private final PackIdentity pack;
        private long readNanos;
        private long bytes;
        private long calls;
        private boolean recorded;

        private MeasuredInputStream(InputStream in, String phase, PackIdentity pack) {
            super(in);
            this.phase = phase;
            this.pack = pack;
        }

        @Override
        public int read() throws IOException {
            long started = System.nanoTime();
            try {
                int value = super.read();
                if (value >= 0) bytes++;
                return value;
            } finally {
                readNanos += System.nanoTime() - started;
                calls++;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            long started = System.nanoTime();
            try {
                int read = super.read(b, off, len);
                if (read > 0) bytes += read;
                return read;
            } finally {
                readNanos += System.nanoTime() - started;
                calls++;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!recorded) {
                    recorded = true;
                    record("resource.read_bytes", phase, pack, readNanos, calls, bytes);
                }
            }
        }
    }
}
