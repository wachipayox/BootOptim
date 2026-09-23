package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, opt-in counters for the exact ModelManager resource-input callsites. */
public final class ModelInputDeepProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelInputDeep");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final AtomicReference<Trace> ACTIVE = new AtomicReference<>();
    private static final ConcurrentLinkedQueue<Trace> COMPLETED = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<Task> TASK = new ThreadLocal<>();

    private ModelInputDeepProfiler() {}

    public static Trace begin() {
        if (!VarianceProbe.enabled()) return null;
        Trace trace = new Trace(NEXT_ID.incrementAndGet());
        if (!ACTIVE.compareAndSet(null, trace)) trace.overlap = true;
        return trace;
    }

    public static void observe(Trace trace, CompletableFuture<?> future) {
        if (trace == null || future == null) return;
        future.whenComplete((value, error) -> {
            trace.complete = error == null ? "success" : error.getClass().getSimpleName();
            ACTIVE.compareAndSet(trace, null);
            COMPLETED.add(trace);
        });
    }

    public static int activeId() {
        Trace trace = ACTIVE.get();
        return trace == null ? -1 : trace.id;
    }

    public static void modelsListed(Map<ResourceLocation, Resource> resources) {
        Trace trace = ACTIVE.get();
        if (trace == null) return;
        trace.modelKeys = resources.size();
        long hash = 0;
        for (var entry : resources.entrySet()) {
            hash += mix(entry.getKey().hashCode(), entry.getValue().sourcePackId().hashCode());
        }
        trace.modelSourceFingerprint = hash;
    }

    public static void statesListed(Map<ResourceLocation, List<Resource>> resources) {
        Trace trace = ACTIVE.get();
        if (trace == null) return;
        trace.stateKeys = resources.size();
        int count = 0;
        long hash = 0;
        for (var entry : resources.entrySet()) {
            long entryHash = entry.getKey().hashCode();
            for (Resource source : entry.getValue()) {
                entryHash = 31 * entryHash + source.sourcePackId().hashCode();
                count++;
            }
            hash += mix(entry.getKey().hashCode(), entryHash);
        }
        trace.stateResources = count;
        trace.stateSourceFingerprint = hash;
    }

    public static void beginModelTask(Resource source) {
        Trace trace = ACTIVE.get();
        if (trace != null) TASK.set(new Task(trace, "model", source.sourcePackId()));
    }

    public static void beginStateTask() {
        Trace trace = ACTIVE.get();
        if (trace != null) TASK.set(new Task(trace, "state", null));
    }

    public static void endTask(boolean success) {
        Task task = TASK.get();
        TASK.remove();
        if (task == null) return;
        long elapsed = Math.max(0L, System.nanoTime() - task.started);
        if (task.domain.equals("model")) {
            task.trace.modelTasks.increment();
            task.trace.modelTaskNanos.add(elapsed);
            task.trace.modelMaxTask.accumulateAndGet(elapsed, Math::max);
            if (!success) task.trace.modelFailures.increment();
            task.trace.pack("model", task.pack).task(elapsed, success);
        } else {
            task.trace.stateTasks.increment();
            task.trace.stateTaskNanos.add(elapsed);
            task.trace.stateMaxTask.accumulateAndGet(elapsed, Math::max);
            if (!success) task.trace.stateFailures.increment();
        }
    }

    public static void opened(Resource source, long elapsed, boolean success) {
        Task task = TASK.get();
        if (task == null) return;
        task.pack = source.sourcePackId();
        task.trace.pack(task.domain, task.pack).open(elapsed, success);
    }

    public static void parsed(long elapsed, boolean success) {
        Task task = TASK.get();
        if (task == null) return;
        task.trace.pack(task.domain, task.pack).parse(elapsed, success);
    }

    /** Called after Window.updateDisplay, outside reload and overlay rendering. */
    public static void emitCompletedAfterFrame() {
        Trace trace;
        while ((trace = COMPLETED.poll()) != null) trace.emit();
    }

    private static long mix(long left, long right) {
        long x = left * 0x9E3779B97F4A7C15L + right;
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private static String token(String text) {
        return text == null ? "unknown" : text.replaceAll("[^A-Za-z0-9_.$:/#-]", "_");
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static final class Task {
        private final Trace trace;
        private final String domain;
        private final long started = System.nanoTime();
        private String pack;

        private Task(Trace trace, String domain, String pack) {
            this.trace = trace;
            this.domain = domain;
            this.pack = pack;
        }
    }

    public static final class Trace {
        private final int id;
        private final ConcurrentHashMap<String, Pack> packs = new ConcurrentHashMap<>();
        private final LongAdder modelTasks = new LongAdder();
        private final LongAdder modelFailures = new LongAdder();
        private final LongAdder modelTaskNanos = new LongAdder();
        private final AtomicLong modelMaxTask = new AtomicLong();
        private final LongAdder stateTasks = new LongAdder();
        private final LongAdder stateFailures = new LongAdder();
        private final LongAdder stateTaskNanos = new LongAdder();
        private final AtomicLong stateMaxTask = new AtomicLong();
        private volatile int modelKeys = -1;
        private volatile int stateKeys = -1;
        private volatile int stateResources = -1;
        private volatile long modelSourceFingerprint;
        private volatile long stateSourceFingerprint;
        private volatile boolean overlap;
        private volatile String complete = "pending";

        private Trace(int id) { this.id = id; }

        private Pack pack(String domain, String packId) {
            String key = domain + "/" + (packId == null ? "unknown" : packId);
            return packs.computeIfAbsent(key, unused -> new Pack(domain, packId));
        }

        private void emit() {
            LOGGER.info("BOOTOPTIM_MODEL_DEEP reload_id={} result={} overlap={} model_keys={} model_tasks={} model_failures={} model_task_ms_sum={} model_max_task_ms={} model_source_fp={} state_keys={} state_resources={} state_tasks={} state_failures={} state_task_ms_sum={} state_max_task_ms={} state_source_fp={} pack_rows={}",
                    id, token(complete), overlap, modelKeys, modelTasks.sum(), modelFailures.sum(),
                    ms(modelTaskNanos.sum()), ms(modelMaxTask.get()), Long.toUnsignedString(modelSourceFingerprint, 16),
                    stateKeys, stateResources, stateTasks.sum(), stateFailures.sum(),
                    ms(stateTaskNanos.sum()), ms(stateMaxTask.get()), Long.toUnsignedString(stateSourceFingerprint, 16),
                    packs.size());
            List<Pack> rows = new ArrayList<>(packs.values());
            rows.sort(Comparator.comparing((Pack p) -> p.domain).thenComparing(p -> p.pack == null ? "" : p.pack));
            for (Pack row : rows) row.emit(id);
        }
    }

    private static final class Pack {
        private final String domain;
        private final String pack;
        private final LongAdder tasks = new LongAdder();
        private final LongAdder taskNanos = new LongAdder();
        private final LongAdder opens = new LongAdder();
        private final LongAdder openNanos = new LongAdder();
        private final LongAdder parses = new LongAdder();
        private final LongAdder parseNanos = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final AtomicLong maxOpen = new AtomicLong();
        private final AtomicLong maxParse = new AtomicLong();

        private Pack(String domain, String pack) { this.domain = domain; this.pack = pack; }

        private void task(long nanos, boolean success) {
            tasks.increment(); taskNanos.add(nanos);
            if (!success) failures.increment();
        }

        private void open(long nanos, boolean success) {
            opens.increment(); openNanos.add(nanos); maxOpen.accumulateAndGet(nanos, Math::max);
            if (!success) failures.increment();
        }

        private void parse(long nanos, boolean success) {
            parses.increment(); parseNanos.add(nanos); maxParse.accumulateAndGet(nanos, Math::max);
            if (!success) failures.increment();
        }

        private void emit(int id) {
            LOGGER.info("BOOTOPTIM_MODEL_PACK reload_id={} domain={} pack={} tasks={} task_ms_sum={} opens={} open_ms_sum={} max_open_ms={} parses={} parse_ms_sum={} max_parse_ms={} failures={}",
                    id, domain, token(pack), tasks.sum(), ms(taskNanos.sum()), opens.sum(), ms(openNanos.sum()),
                    ms(maxOpen.get()), parses.sum(), ms(parseNanos.sum()), ms(maxParse.get()), failures.sum());
        }
    }
}
