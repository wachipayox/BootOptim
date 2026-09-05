package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Diagnostic-only audit for exact repeated Ponder VoxelShaper rotations.
 * It never substitutes a result: stock rotatedCopy always executes and is returned.
 */
public final class VoxelShaperExactReuseProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileVoxelShaperExactReuse");
    private static final ThreadLocal<CallContext> CONTEXT = new ThreadLocal<>();
    private static final Map<ExactInputKey, FirstResult> FIRST_RESULTS = new HashMap<>();
    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder ZERO_ROTATION_CALLS = new LongAdder();
    private static final LongAdder DUPLICATE_CALLS = new LongAdder();
    private static final LongAdder TOTAL_STOCK_WALL_NANOS = new LongAdder();
    private static final LongAdder TOTAL_STOCK_CPU_NANOS = new LongAdder();
    private static final LongAdder DUPLICATE_STOCK_WALL_NANOS = new LongAdder();
    private static final LongAdder DUPLICATE_STOCK_CPU_NANOS = new LongAdder();
    private static final LongAdder SIGNATURE_WALL_NANOS = new LongAdder();
    private static final LongAdder SIGNATURE_CPU_NANOS = new LongAdder();
    private static final LongAdder VERIFY_WALL_NANOS = new LongAdder();
    private static final LongAdder VERIFY_CPU_NANOS = new LongAdder();
    private static final LongAdder OUTPUT_MISMATCHES = new LongAdder();
    private static final AtomicLong MAX_KEY_LONGS = new AtomicLong();
    private static final List<String> FIRST_MISMATCHES = new ArrayList<>();
    private static volatile boolean versionChecked;
    private static volatile boolean versionCompatible;
    private static volatile String createVersion = "unknown";
    private static volatile String ponderVersion = "unknown";
    private static volatile boolean reported;

    private VoxelShaperExactReuseProfiler() {}

    public static void begin(VoxelShape source, Vec3 rotation) {
        CONTEXT.remove();
        if (!ENABLED || !checkVersionGate()) return;
        if (rotation.equals(Vec3.ZERO)) {
            ZERO_ROTATION_CALLS.increment();
            return;
        }

        long signatureCpuStart = cpuNow();
        long signatureWallStart = System.nanoTime();
        ExactInputKey key = ExactInputKey.capture(source, rotation);
        SIGNATURE_WALL_NANOS.add(System.nanoTime() - signatureWallStart);
        addCpu(SIGNATURE_CPU_NANOS, signatureCpuStart);
        MAX_KEY_LONGS.accumulateAndGet(key.data.length, Math::max);

        CALLS.increment();
        CONTEXT.set(new CallContext(key, System.nanoTime(), cpuNow()));
    }

    public static void finish(VoxelShape stockResult) {
        CallContext context = CONTEXT.get();
        if (context == null) return;
        CONTEXT.remove();

        long wall = System.nanoTime() - context.stockWallStart;
        long cpu = cpuElapsed(context.stockCpuStart);
        TOTAL_STOCK_WALL_NANOS.add(wall);
        if (cpu >= 0L) TOTAL_STOCK_CPU_NANOS.add(cpu);

        FirstResult prior;
        synchronized (FIRST_RESULTS) {
            prior = FIRST_RESULTS.get(context.key);
            if (prior == null) {
                FIRST_RESULTS.put(context.key, FirstResult.capture(stockResult));
                return;
            }
        }

        DUPLICATE_CALLS.increment();
        DUPLICATE_STOCK_WALL_NANOS.add(wall);
        if (cpu >= 0L) DUPLICATE_STOCK_CPU_NANOS.add(cpu);

        long verifyCpuStart = cpuNow();
        long verifyWallStart = System.nanoTime();
        String mismatch = prior.compare(stockResult);
        VERIFY_WALL_NANOS.add(System.nanoTime() - verifyWallStart);
        addCpu(VERIFY_CPU_NANOS, verifyCpuStart);
        if (mismatch != null) {
            OUTPUT_MISMATCHES.increment();
            rememberMismatch(mismatch);
        }
    }

    public static void onMainMenu() {
        if (reported || !ENABLED) return;
        reported = true;
        long calls = CALLS.sum();
        long duplicates = DUPLICATE_CALLS.sum();
        long unique;
        synchronized (FIRST_RESULTS) {
            unique = FIRST_RESULTS.size();
        }
        double duplicatePct = calls == 0L ? 0.0D : duplicates * 100.0D / calls;
        double duplicateCpuPct = TOTAL_STOCK_CPU_NANOS.sum() == 0L ? 0.0D
                : DUPLICATE_STOCK_CPU_NANOS.sum() * 100.0D / TOTAL_STOCK_CPU_NANOS.sum();
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_EXACT_REUSE status=complete create_version={} ponder_version={} calls={} zero_rotation_calls={} unique_keys={} duplicate_calls={} duplicate_call_pct={} total_stock_wall_ms={} total_stock_cpu_ms={} duplicate_stock_wall_ms={} duplicate_stock_cpu_ms={} duplicate_stock_cpu_pct={} signature_wall_ms={} signature_cpu_ms={} verify_wall_ms={} verify_cpu_ms={} output_mismatches={} max_key_longs={} cache_action=none stock_always_returned=true",
                createVersion, ponderVersion, calls, ZERO_ROTATION_CALLS.sum(), unique, duplicates,
                format(duplicatePct), millis(TOTAL_STOCK_WALL_NANOS.sum()), millis(TOTAL_STOCK_CPU_NANOS.sum()),
                millis(DUPLICATE_STOCK_WALL_NANOS.sum()), millis(DUPLICATE_STOCK_CPU_NANOS.sum()),
                format(duplicateCpuPct), millis(SIGNATURE_WALL_NANOS.sum()), millis(SIGNATURE_CPU_NANOS.sum()),
                millis(VERIFY_WALL_NANOS.sum()), millis(VERIFY_CPU_NANOS.sum()), OUTPUT_MISMATCHES.sum(),
                MAX_KEY_LONGS.get());
        if (!FIRST_MISMATCHES.isEmpty()) {
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_EXACT_REUSE_MISMATCHES count={} first={}",
                    FIRST_MISMATCHES.size(), FIRST_MISMATCHES);
        }
    }

    private static synchronized boolean checkVersionGate() {
        if (versionChecked) return versionCompatible;
        versionChecked = true;
        try {
            createVersion = ModList.get().getModContainerById("create")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("missing");
            ponderVersion = ModList.get().getModContainerById("ponder")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("missing");
            versionCompatible = "6.0.10".equals(createVersion)
                    && ("1.0.82".equals(ponderVersion) || ponderVersion.startsWith("1.0.82+"));
        } catch (RuntimeException exception) {
            versionCompatible = false;
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_EXACT_REUSE status=disabled reason=version_gate_failure", exception);
        }
        return versionCompatible;
    }

    private static long cpuNow() {
        if (!THREAD_BEAN.isCurrentThreadCpuTimeSupported() || !THREAD_BEAN.isThreadCpuTimeEnabled()) return -1L;
        return THREAD_BEAN.getCurrentThreadCpuTime();
    }

    private static long cpuElapsed(long start) {
        if (start < 0L) return -1L;
        long end = THREAD_BEAN.getCurrentThreadCpuTime();
        return end >= start ? end - start : -1L;
    }

    private static void addCpu(LongAdder destination, long start) {
        long elapsed = cpuElapsed(start);
        if (elapsed >= 0L) destination.add(elapsed);
    }

    private static synchronized void rememberMismatch(String mismatch) {
        if (FIRST_MISMATCHES.size() < 8) FIRST_MISMATCHES.add(mismatch);
    }

    private static String millis(long nanos) {
        return format(nanos / 1_000_000.0D);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class CallContext {
        final ExactInputKey key;
        final long stockWallStart;
        final long stockCpuStart;

        CallContext(ExactInputKey key, long stockWallStart, long stockCpuStart) {
            this.key = key;
            this.stockWallStart = stockWallStart;
            this.stockCpuStart = stockCpuStart;
        }
    }

    /** Exact source representation + exact resolved rotation; equals never relies on hash alone. */
    private static final class ExactInputKey {
        final String shapeClass;
        final long rotationX;
        final long rotationY;
        final long rotationZ;
        final long[] data;
        final int hash;

        private ExactInputKey(String shapeClass, long rotationX, long rotationY, long rotationZ, long[] data) {
            this.shapeClass = shapeClass;
            this.rotationX = rotationX;
            this.rotationY = rotationY;
            this.rotationZ = rotationZ;
            this.data = data;
            int h = Objects.hash(shapeClass, rotationX, rotationY, rotationZ);
            this.hash = 31 * h + Arrays.hashCode(data);
        }

        static ExactInputKey capture(VoxelShape source, Vec3 rotation) {
            List<AABB> boxes = source.toAabbs();
            DoubleList x = source.getCoords(Direction.Axis.X);
            DoubleList y = source.getCoords(Direction.Axis.Y);
            DoubleList z = source.getCoords(Direction.Axis.Z);
            int size = 4 + x.size() + y.size() + z.size() + boxes.size() * 6;
            long[] data = new long[size];
            int i = 0;
            data[i++] = x.size();
            for (int p = 0; p < x.size(); p++) data[i++] = Double.doubleToLongBits(x.getDouble(p));
            data[i++] = y.size();
            for (int p = 0; p < y.size(); p++) data[i++] = Double.doubleToLongBits(y.getDouble(p));
            data[i++] = z.size();
            for (int p = 0; p < z.size(); p++) data[i++] = Double.doubleToLongBits(z.getDouble(p));
            data[i++] = boxes.size();
            for (AABB box : boxes) {
                data[i++] = Double.doubleToLongBits(box.minX);
                data[i++] = Double.doubleToLongBits(box.minY);
                data[i++] = Double.doubleToLongBits(box.minZ);
                data[i++] = Double.doubleToLongBits(box.maxX);
                data[i++] = Double.doubleToLongBits(box.maxY);
                data[i++] = Double.doubleToLongBits(box.maxZ);
            }
            return new ExactInputKey(
                    source.getClass().getName(),
                    Double.doubleToLongBits(rotation.x),
                    Double.doubleToLongBits(rotation.y),
                    Double.doubleToLongBits(rotation.z),
                    data);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ExactInputKey that)) return false;
            return rotationX == that.rotationX && rotationY == that.rotationY && rotationZ == that.rotationZ
                    && shapeClass.equals(that.shapeClass) && Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** Exact representation snapshot of the first stock output for verifier-only comparison. */
    private static final class FirstResult {
        final String shapeClass;
        final long[] data;

        private FirstResult(String shapeClass, long[] data) {
            this.shapeClass = shapeClass;
            this.data = data;
        }

        static FirstResult capture(VoxelShape result) {
            ExactInputKey snapshot = ExactInputKey.capture(result, Vec3.ZERO);
            return new FirstResult(snapshot.shapeClass, snapshot.data);
        }

        String compare(VoxelShape result) {
            ExactInputKey current = ExactInputKey.capture(result, Vec3.ZERO);
            if (!shapeClass.equals(current.shapeClass)) return "representation_class";
            if (!Arrays.equals(data, current.data)) return "representation_bits";
            return null;
        }
    }
}
