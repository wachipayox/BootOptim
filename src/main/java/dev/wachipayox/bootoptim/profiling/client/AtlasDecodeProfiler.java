package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.textures.SpriteContentsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only decomposition of atlas sprite loading.
 *
 * <p>There is deliberately no per-sprite logging and no global lock on the hot path. Each worker accumulates
 * one mutable context while a sprite is being loaded; completed samples are folded into LongAdder-backed
 * namespace/pack aggregates. A reload-scoped key set is retained only to measure the optimistic ceiling for
 * reusing already-materialized encoded bytes.</p>
 */
public final class AtlasDecodeProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/AtlasDecode");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileAtlasDecode")
            || Boolean.getBoolean(StartupProfiler.PROFILE_PROPERTY);
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME = enableThreadCpuTime();
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();
    private static final Set<ResourceKey> SEEN = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Aggregate> NAMESPACES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Aggregate> PACKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, StreamAggregate> STREAMS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<DimensionKey, LongAdder> DIMENSIONS = new ConcurrentHashMap<>();
    private static final Aggregate ALL = new Aggregate();
    private static final AtomicBoolean DUMPED = new AtomicBoolean();

    private AtlasDecodeProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static Context begin(ResourceLocation id, String packId) {
        if (!ENABLED) {
            return null;
        }
        Context previous = ACTIVE.get();
        Context context = new Context(
                previous,
                id == null ? "unknown" : id.toString(),
                id == null ? "unknown" : id.getNamespace(),
                packId == null ? "unknown" : packId,
                wallNow(),
                cpuNow());
        context.firstResourceOccurrence = SEEN.add(new ResourceKey(context.pack, context.id));
        ACTIVE.set(context);
        return context;
    }

    public static SpriteContents finish(Context context, SpriteContents result) {
        if (context == null) {
            return result;
        }
        long wallEnd = wallNow();
        long cpuEnd = cpuNow();
        context.inclusiveWall = elapsed(context.startWall, wallEnd);
        context.inclusiveCpu = elapsedCpu(context.startCpu, cpuEnd);
        if (result != null && context.width <= 0) {
            context.width = result.width();
            context.height = result.height();
        }
        fold(context);
        if (context.previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(context.previous);
        }
        return result;
    }

    public static void abort(Context context) {
        if (context == null) {
            return;
        }
        finish(context, null);
    }

    public static SpriteContentsConstructor wrapConstructor(SpriteContentsConstructor delegate) {
        if (!ENABLED || delegate == null) {
            return delegate;
        }
        return (id, frameSize, image, metadata) -> {
            Context context = ACTIVE.get();
            if (context == null) {
                return delegate.create(id, frameSize, image, metadata);
            }
            long wallStart = wallNow();
            long cpuStart = cpuNow();
            if (context.nativeImageEndWall > 0L) {
                context.animationWall += elapsed(context.nativeImageEndWall, wallStart);
                context.animationCpu += elapsedCpu(context.nativeImageEndCpu, cpuStart);
            }
            try {
                return delegate.create(id, frameSize, image, metadata);
            } finally {
                context.constructorWall += elapsed(wallStart, wallNow());
                context.constructorCpu += elapsedCpu(cpuStart, cpuNow());
            }
        };
    }

    public static void metadataStart() {
        Context context = ACTIVE.get();
        if (context != null && context.metadataStartWall == 0L) {
            context.metadataStartWall = wallNow();
            context.metadataStartCpu = cpuNow();
        }
    }

    public static void metadataEnd() {
        Context context = ACTIVE.get();
        if (context != null && context.metadataStartWall != 0L) {
            context.metadataCallWall += elapsed(context.metadataStartWall, wallNow());
            context.metadataCallCpu += elapsedCpu(context.metadataStartCpu, cpuNow());
            context.metadataStartWall = 0L;
            context.metadataStartCpu = 0L;
        }
    }

    public static void resourceOpenStart() {
        Context context = ACTIVE.get();
        if (context == null) {
            return;
        }
        long wall = wallNow();
        long cpu = cpuNow();
        if (context.preOpenWall == 0L) {
            context.preOpenWall = elapsed(context.startWall, wall);
            context.preOpenCpu = elapsedCpu(context.startCpu, cpu);
        }
        context.openStartWall = wall;
        context.openStartCpu = cpu;
    }

    public static InputStream resourceOpenEnd(InputStream stream) {
        Context context = ACTIVE.get();
        if (context == null || context.openStartWall == 0L || stream == null) {
            return stream;
        }
        context.openWall += elapsed(context.openStartWall, wallNow());
        context.openCpu += elapsedCpu(context.openStartCpu, cpuNow());
        context.openStartWall = 0L;
        context.openStartCpu = 0L;
        context.streamClass = stream.getClass().getName();
        return new ProfiledInputStream(stream, context);
    }

    public static void nativeImageStart() {
        Context context = ACTIVE.get();
        if (context != null && context.nativeImageStartWall == 0L) {
            context.nativeImageStartWall = wallNow();
            context.nativeImageStartCpu = cpuNow();
        }
    }

    public static void nativeImageEnd(int width, int height) {
        Context context = ACTIVE.get();
        if (context == null || context.nativeImageStartWall == 0L) {
            return;
        }
        long wall = wallNow();
        long cpu = cpuNow();
        context.nativeImageWall += elapsed(context.nativeImageStartWall, wall);
        context.nativeImageCpu += elapsedCpu(context.nativeImageStartCpu, cpu);
        context.nativeImageStartWall = 0L;
        context.nativeImageStartCpu = 0L;
        context.nativeImageEndWall = wall;
        context.nativeImageEndCpu = cpu;
        if (width > 0 && height > 0) {
            context.width = width;
            context.height = height;
        }
    }

    public static void textureRead(long wallNanos, long cpuNanos, int encodedBytes) {
        Context context = ACTIVE.get();
        if (context == null) {
            return;
        }
        context.textureReadWall += wallNanos;
        context.textureReadCpu += cpuNanos;
        if (encodedBytes > 0) {
            context.encodedBytes = Math.max(context.encodedBytes, encodedBytes);
        }
    }

    public static void byteBufferDecode(long wallNanos, long cpuNanos) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.byteBufferDecodeWall += wallNanos;
            context.byteBufferDecodeCpu += cpuNanos;
        }
    }

    public static void pngHeader(long wallNanos, long cpuNanos) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.pngHeaderWall += wallNanos;
            context.pngHeaderCpu += cpuNanos;
        }
    }

    public static void stbiDecode(long wallNanos, long cpuNanos) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.stbiWall += wallNanos;
            context.stbiCpu += cpuNanos;
        }
    }

    public static long wallNow() {
        return System.nanoTime();
    }

    public static long cpuNow() {
        return CPU_TIME ? THREAD_CPU.getCurrentThreadCpuTime() : -1L;
    }

    public static long elapsed(long start, long end) {
        return start <= 0L || end < start ? 0L : end - start;
    }

    public static long elapsedCpu(long start, long end) {
        return start < 0L || end < start ? 0L : end - start;
    }

    public static void dump() {
        if (!ENABLED || !DUMPED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info(
                "BOOTOPTIM_ATLAS_DECODE kind=summary cpu_time={} unique_resources={} namespaces={} packs={} stream_classes={} dimension_pairs={}",
                CPU_TIME,
                SEEN.size(),
                NAMESPACES.size(),
                PACKS.size(),
                STREAMS.size(),
                DIMENSIONS.size());
        logAggregate("all", "all", ALL);

        List<Map.Entry<String, Aggregate>> namespaces = new ArrayList<>(NAMESPACES.entrySet());
        namespaces.sort(Comparator.comparingLong((Map.Entry<String, Aggregate> e) -> e.getValue().inclusiveWall.sum()).reversed());
        for (int i = 0; i < Math.min(24, namespaces.size()); i++) {
            Map.Entry<String, Aggregate> entry = namespaces.get(i);
            logAggregate("namespace", entry.getKey(), entry.getValue());
        }

        List<Map.Entry<String, Aggregate>> packs = new ArrayList<>(PACKS.entrySet());
        packs.sort(Comparator.comparingLong((Map.Entry<String, Aggregate> e) -> e.getValue().inclusiveWall.sum()).reversed());
        for (int i = 0; i < Math.min(16, packs.size()); i++) {
            Map.Entry<String, Aggregate> entry = packs.get(i);
            logAggregate("pack", entry.getKey(), entry.getValue());
        }

        List<Map.Entry<String, StreamAggregate>> streams = new ArrayList<>(STREAMS.entrySet());
        streams.sort(Comparator.comparingLong((Map.Entry<String, StreamAggregate> e) -> e.getValue().wall.sum()).reversed());
        for (int i = 0; i < Math.min(16, streams.size()); i++) {
            Map.Entry<String, StreamAggregate> entry = streams.get(i);
            StreamAggregate stat = entry.getValue();
            LOGGER.info(
                    "BOOTOPTIM_ATLAS_DECODE kind=stream_class class={} sprites={} reads={} bytes={} read_wall_ms={} read_cpu_ms={} read_non_cpu_ms={}",
                    entry.getKey(),
                    stat.sprites.sum(),
                    stat.reads.sum(),
                    stat.bytes.sum(),
                    ms(stat.wall.sum()),
                    ms(stat.cpu.sum()),
                    ms(nonCpu(stat.wall.sum(), stat.cpu.sum())));
        }

        List<Map.Entry<DimensionKey, LongAdder>> dimensions = new ArrayList<>(DIMENSIONS.entrySet());
        dimensions.removeIf(entry -> !"decocraft".equals(entry.getKey().namespace));
        dimensions.sort(Comparator.comparingLong((Map.Entry<DimensionKey, LongAdder> e) -> e.getValue().sum()).reversed());
        for (int i = 0; i < Math.min(24, dimensions.size()); i++) {
            Map.Entry<DimensionKey, LongAdder> entry = dimensions.get(i);
            LOGGER.info(
                    "BOOTOPTIM_ATLAS_DECODE kind=decocraft_dimension width={} height={} sprites={}",
                    entry.getKey().width,
                    entry.getKey().height,
                    entry.getValue().sum());
        }
    }

    private static void fold(Context context) {
        if (context.preOpenWall == 0L) {
            context.preOpenWall = context.metadataCallWall;
            context.preOpenCpu = context.metadataCallCpu;
        }
        long metadataCopyWall = positive(context.preOpenWall - context.metadataCallWall);
        long metadataCopyCpu = positive(context.preOpenCpu - context.metadataCallCpu);
        long streamReadWall = context.streamReadWall;
        long streamReadCpu = context.streamReadCpu;
        long stagingWall = positive(context.textureReadWall - streamReadWall);
        long stagingCpu = positive(context.textureReadCpu - streamReadCpu);
        long decodeWrapperWall = positive(context.byteBufferDecodeWall - context.pngHeaderWall - context.stbiWall);
        long decodeWrapperCpu = positive(context.byteBufferDecodeCpu - context.pngHeaderCpu - context.stbiCpu);
        long nativeOuterWall = positive(context.nativeImageWall - context.textureReadWall - context.byteBufferDecodeWall);
        long nativeOuterCpu = positive(context.nativeImageCpu - context.textureReadCpu - context.byteBufferDecodeCpu);
        long accountedWall = context.metadataCallWall
                + metadataCopyWall
                + context.openWall
                + streamReadWall
                + stagingWall
                + context.pngHeaderWall
                + context.stbiWall
                + decodeWrapperWall
                + nativeOuterWall
                + context.animationWall
                + context.constructorWall;
        long accountedCpu = context.metadataCallCpu
                + metadataCopyCpu
                + context.openCpu
                + streamReadCpu
                + stagingCpu
                + context.pngHeaderCpu
                + context.stbiCpu
                + decodeWrapperCpu
                + nativeOuterCpu
                + context.animationCpu
                + context.constructorCpu;
        context.residualWall = positive(context.inclusiveWall - accountedWall);
        context.residualCpu = positive(context.inclusiveCpu - accountedCpu);

        ALL.add(context, metadataCopyWall, metadataCopyCpu, stagingWall, stagingCpu, decodeWrapperWall, decodeWrapperCpu, nativeOuterWall, nativeOuterCpu);
        NAMESPACES.computeIfAbsent(context.namespace, ignored -> new Aggregate())
                .add(context, metadataCopyWall, metadataCopyCpu, stagingWall, stagingCpu, decodeWrapperWall, decodeWrapperCpu, nativeOuterWall, nativeOuterCpu);
        PACKS.computeIfAbsent(context.pack, ignored -> new Aggregate())
                .add(context, metadataCopyWall, metadataCopyCpu, stagingWall, stagingCpu, decodeWrapperWall, decodeWrapperCpu, nativeOuterWall, nativeOuterCpu);

        if (context.streamClass != null) {
            STREAMS.computeIfAbsent(context.streamClass, ignored -> new StreamAggregate()).add(context);
        }
        if (context.width > 0 && context.height > 0) {
            DIMENSIONS.computeIfAbsent(new DimensionKey(context.namespace, context.width, context.height), ignored -> new LongAdder()).increment();
        }
    }

    private static void logAggregate(String kind, String name, Aggregate a) {
        long calls = a.calls.sum();
        long inclusive = a.inclusiveWall.sum();
        long encoded = a.encodedBytes.sum();
        long decoded = a.decodedBytes.sum();
        LOGGER.info(
                "BOOTOPTIM_ATLAS_DECODE kind={} name={} calls={} repeats={} repeat_task_ms={} inclusive_task_ms={} inclusive_cpu_ms={} metadata_call_ms={} metadata_copy_ms={} open_ms={} stream_read_ms={} stream_cpu_ms={} stream_non_cpu_ms={} staging_copy_ms={} png_header_ms={} stbi_decode_ms={} decode_wrapper_ms={} native_outer_ms={} animation_frame_ms={} constructor_ms={} residual_ms={} encoded_bytes={} decoded_rgba_bytes={} decoded_to_encoded_ratio={} max_pixels={}",
                kind,
                name,
                calls,
                a.repeats.sum(),
                ms(a.repeatWall.sum()),
                ms(inclusive),
                ms(a.inclusiveCpu.sum()),
                ms(a.metadataCallWall.sum()),
                ms(a.metadataCopyWall.sum()),
                ms(a.openWall.sum()),
                ms(a.streamReadWall.sum()),
                ms(a.streamReadCpu.sum()),
                ms(nonCpu(a.streamReadWall.sum(), a.streamReadCpu.sum())),
                ms(a.stagingWall.sum()),
                ms(a.pngHeaderWall.sum()),
                ms(a.stbiWall.sum()),
                ms(a.decodeWrapperWall.sum()),
                ms(a.nativeOuterWall.sum()),
                ms(a.animationWall.sum()),
                ms(a.constructorWall.sum()),
                ms(a.residualWall.sum()),
                encoded,
                decoded,
                ratio(decoded, encoded),
                a.maxPixels.get());
    }

    private static boolean enableThreadCpuTime() {
        try {
            if (!THREAD_CPU.isCurrentThreadCpuTimeSupported()) {
                return false;
            }
            if (!THREAD_CPU.isThreadCpuTimeEnabled()) {
                THREAD_CPU.setThreadCpuTimeEnabled(true);
            }
            return THREAD_CPU.isThreadCpuTimeEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long nonCpu(long wall, long cpu) {
        return positive(wall - cpu);
    }

    private static long positive(long value) {
        return Math.max(0L, value);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return "0.000";
        }
        return String.format(Locale.ROOT, "%.3f", (double) numerator / (double) denominator);
    }

    public static final class Context {
        private final Context previous;
        private final String id;
        private final String namespace;
        private final String pack;
        private final long startWall;
        private final long startCpu;
        private boolean firstResourceOccurrence;
        private long inclusiveWall;
        private long inclusiveCpu;
        private long metadataStartWall;
        private long metadataStartCpu;
        private long metadataCallWall;
        private long metadataCallCpu;
        private long preOpenWall;
        private long preOpenCpu;
        private long openStartWall;
        private long openStartCpu;
        private long openWall;
        private long openCpu;
        private long streamReadWall;
        private long streamReadCpu;
        private long streamReads;
        private long streamBytes;
        private String streamClass;
        private long textureReadWall;
        private long textureReadCpu;
        private long byteBufferDecodeWall;
        private long byteBufferDecodeCpu;
        private long pngHeaderWall;
        private long pngHeaderCpu;
        private long stbiWall;
        private long stbiCpu;
        private long nativeImageStartWall;
        private long nativeImageStartCpu;
        private long nativeImageWall;
        private long nativeImageCpu;
        private long nativeImageEndWall;
        private long nativeImageEndCpu;
        private long animationWall;
        private long animationCpu;
        private long constructorWall;
        private long constructorCpu;
        private long residualWall;
        private long residualCpu;
        private int encodedBytes;
        private int width;
        private int height;

        private Context(Context previous, String id, String namespace, String pack, long startWall, long startCpu) {
            this.previous = previous;
            this.id = id;
            this.namespace = namespace;
            this.pack = pack;
            this.startWall = startWall;
            this.startCpu = startCpu;
        }
    }

    private static final class ProfiledInputStream extends FilterInputStream {
        private final Context context;

        private ProfiledInputStream(InputStream in, Context context) {
            super(in);
            this.context = context;
        }

        @Override
        public int read() throws IOException {
            long wall = wallNow();
            long cpu = cpuNow();
            int result = in.read();
            recordRead(wall, cpu, result < 0 ? 0 : 1);
            return result;
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            long wall = wallNow();
            long cpu = cpuNow();
            int result = in.read(bytes);
            recordRead(wall, cpu, result);
            return result;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            long wall = wallNow();
            long cpu = cpuNow();
            int result = in.read(bytes, offset, length);
            recordRead(wall, cpu, result);
            return result;
        }

        private void recordRead(long wallStart, long cpuStart, int count) {
            context.streamReadWall += elapsed(wallStart, wallNow());
            context.streamReadCpu += elapsedCpu(cpuStart, cpuNow());
            context.streamReads++;
            if (count > 0) {
                context.streamBytes += count;
                if (context.streamBytes <= Integer.MAX_VALUE) {
                    context.encodedBytes = (int) context.streamBytes;
                }
            }
        }
    }

    private static final class Aggregate {
        private final LongAdder calls = new LongAdder();
        private final LongAdder repeats = new LongAdder();
        private final LongAdder repeatWall = new LongAdder();
        private final LongAdder inclusiveWall = new LongAdder();
        private final LongAdder inclusiveCpu = new LongAdder();
        private final LongAdder metadataCallWall = new LongAdder();
        private final LongAdder metadataCopyWall = new LongAdder();
        private final LongAdder openWall = new LongAdder();
        private final LongAdder streamReadWall = new LongAdder();
        private final LongAdder streamReadCpu = new LongAdder();
        private final LongAdder stagingWall = new LongAdder();
        private final LongAdder pngHeaderWall = new LongAdder();
        private final LongAdder stbiWall = new LongAdder();
        private final LongAdder decodeWrapperWall = new LongAdder();
        private final LongAdder nativeOuterWall = new LongAdder();
        private final LongAdder animationWall = new LongAdder();
        private final LongAdder constructorWall = new LongAdder();
        private final LongAdder residualWall = new LongAdder();
        private final LongAdder encodedBytes = new LongAdder();
        private final LongAdder decodedBytes = new LongAdder();
        private final AtomicLong maxPixels = new AtomicLong();

        private void add(
                Context c,
                long metadataCopyWall,
                long metadataCopyCpu,
                long stagingWall,
                long stagingCpu,
                long decodeWrapperWall,
                long decodeWrapperCpu,
                long nativeOuterWall,
                long nativeOuterCpu) {
            calls.increment();
            if (!c.firstResourceOccurrence) {
                repeats.increment();
                repeatWall.add(c.inclusiveWall);
            }
            inclusiveWall.add(c.inclusiveWall);
            inclusiveCpu.add(c.inclusiveCpu);
            metadataCallWall.add(c.metadataCallWall);
            this.metadataCopyWall.add(metadataCopyWall);
            openWall.add(c.openWall);
            streamReadWall.add(c.streamReadWall);
            streamReadCpu.add(c.streamReadCpu);
            this.stagingWall.add(stagingWall);
            pngHeaderWall.add(c.pngHeaderWall);
            stbiWall.add(c.stbiWall);
            this.decodeWrapperWall.add(decodeWrapperWall);
            this.nativeOuterWall.add(nativeOuterWall);
            animationWall.add(c.animationWall);
            constructorWall.add(c.constructorWall);
            residualWall.add(c.residualWall);
            encodedBytes.add(c.encodedBytes);
            if (c.width > 0 && c.height > 0) {
                long pixels = (long) c.width * (long) c.height;
                decodedBytes.add(pixels * 4L);
                maxPixels.accumulateAndGet(pixels, Math::max);
            }
        }
    }

    private static final class StreamAggregate {
        private final LongAdder sprites = new LongAdder();
        private final LongAdder reads = new LongAdder();
        private final LongAdder bytes = new LongAdder();
        private final LongAdder wall = new LongAdder();
        private final LongAdder cpu = new LongAdder();

        private void add(Context c) {
            sprites.increment();
            reads.add(c.streamReads);
            bytes.add(c.streamBytes);
            wall.add(c.streamReadWall);
            cpu.add(c.streamReadCpu);
        }
    }

    private record ResourceKey(String pack, String id) {
    }

    private record DimensionKey(String namespace, int width, int height) {
    }
}
