package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.neoforge.client.ClientHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic-only split of vanilla/NeoForge generated-item geometry work.
 *
 * <p>The key measurement is first-vs-repeat time for ItemModelGenerator#getSpans by SpriteContents identity. The span
 * topology depends only on SpriteContents, unlike the BlockElements created later and mutated by NeoForge's seam fix.
 * This directly estimates the safe ceiling of a reload-scoped span-topology cache.</p>
 */
public final class GeneratedItemResidualProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/GeneratedItemResidual");
    private static final ThreadLocal<Deque<Long>> GENERATE_STARTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<Long>> PROCESS_STARTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<SpanFrame>> SPAN_STARTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final IdentityHashMap<SpriteContents, SpriteStats> SPRITES = new IdentityHashMap<>();
    private static final IdentityHashMap<BlockElement, Boolean> GENERATED_ELEMENTS = new IdentityHashMap<>();

    private static volatile boolean active;
    private static long generateCalls;
    private static long generateNanos;
    private static long generatedElements;
    private static long processCalls;
    private static long processNanos;
    private static long spanCalls;
    private static long spanNanos;
    private static long firstSpanNanos;
    private static long repeatSpanNanos;
    private static long repeatSpanCalls;
    private static long seamCalls;
    private static long seamNanos;
    private static long corruptFrames;

    private GeneratedItemResidualProfiler() {}

    public static synchronized void begin() {
        SPRITES.clear();
        GENERATED_ELEMENTS.clear();
        generateCalls = generateNanos = generatedElements = 0L;
        processCalls = processNanos = 0L;
        spanCalls = spanNanos = firstSpanNanos = repeatSpanNanos = repeatSpanCalls = 0L;
        seamCalls = seamNanos = corruptFrames = 0L;
        GENERATE_STARTS.remove();
        PROCESS_STARTS.remove();
        SPAN_STARTS.remove();
        active = true;
    }

    public static void beginGenerate() {
        if (active) GENERATE_STARTS.get().push(System.nanoTime());
    }

    public static void endGenerate(BlockModel generatedModel) {
        if (!active) return;
        Long started = GENERATE_STARTS.get().poll();
        if (started == null) { corrupt(); return; }
        synchronized (GeneratedItemResidualProfiler.class) {
            generateCalls++;
            generateNanos += System.nanoTime() - started;
            if (generatedModel != null) {
                for (BlockElement element : generatedModel.getElements()) {
                    if (GENERATED_ELEMENTS.put(element, Boolean.TRUE) == null) generatedElements++;
                }
            }
        }
    }

    public static synchronized boolean isGeneratedElement(BlockElement element) {
        return active && GENERATED_ELEMENTS.containsKey(element);
    }

    public static void beginProcess() {
        if (active) PROCESS_STARTS.get().push(System.nanoTime());
    }

    public static void endProcess() {
        if (!active) return;
        Long started = PROCESS_STARTS.get().poll();
        if (started == null) { corrupt(); return; }
        synchronized (GeneratedItemResidualProfiler.class) {
            processCalls++;
            processNanos += System.nanoTime() - started;
        }
    }

    public static void beginSpans(SpriteContents sprite) {
        if (!active) return;
        synchronized (GeneratedItemResidualProfiler.class) {
            SpriteStats stats = SPRITES.get(sprite);
            boolean first = stats == null;
            if (first) {
                stats = new SpriteStats(sprite.name().toString(), sprite.width(), sprite.height());
                SPRITES.put(sprite, stats);
            }
            SPAN_STARTS.get().push(new SpanFrame(sprite, System.nanoTime(), first));
        }
    }

    public static void endSpans(SpriteContents sprite, int spanCount) {
        if (!active) return;
        SpanFrame frame = SPAN_STARTS.get().poll();
        if (frame == null || frame.sprite != sprite) { corrupt(); return; }
        long elapsed = System.nanoTime() - frame.startedNanos;
        synchronized (GeneratedItemResidualProfiler.class) {
            SpriteStats stats = SPRITES.get(sprite);
            if (stats == null) { corruptFrames++; return; }
            spanCalls++;
            spanNanos += elapsed;
            stats.calls++;
            stats.spanCountTotal += Math.max(0, spanCount);
            stats.maxNanos = Math.max(stats.maxNanos, elapsed);
            if (frame.first) {
                firstSpanNanos += elapsed;
                stats.firstNanos += elapsed;
            } else {
                repeatSpanCalls++;
                repeatSpanNanos += elapsed;
                stats.repeatNanos += elapsed;
            }
        }
    }

    public static List<BlockElement> profileSeamFix(List<BlockElement> elements, TextureAtlasSprite sprite) {
        if (!active) return ClientHooks.fixItemModelSeams(elements, sprite);
        long started = System.nanoTime();
        try {
            return ClientHooks.fixItemModelSeams(elements, sprite);
        } finally {
            synchronized (GeneratedItemResidualProfiler.class) {
                seamCalls++;
                seamNanos += System.nanoTime() - started;
            }
        }
    }

    public static synchronized void finish() {
        if (!active) return;
        active = false;
        LOGGER.info("BOOTOPTIM_GENERATED_ITEM_RESIDUAL generate_calls={} generate_ms={} generated_elements={} process_calls={} process_ms={} spans_calls={} spans_ms={} unique_sprites={} repeat_span_calls={} first_span_ms={} repeat_span_ms={} repeat_share_percent={} seam_calls={} seam_ms={} corrupt_frames={}",
                generateCalls, ms(generateNanos), generatedElements, processCalls, ms(processNanos), spanCalls, ms(spanNanos), SPRITES.size(), repeatSpanCalls,
                ms(firstSpanNanos), ms(repeatSpanNanos), percent(repeatSpanNanos, spanNanos), seamCalls, ms(seamNanos), corruptFrames);

        List<Map.Entry<SpriteContents, SpriteStats>> sorted = new ArrayList<>(SPRITES.entrySet());
        sorted.sort(Comparator.<Map.Entry<SpriteContents, SpriteStats>>comparingLong(entry -> entry.getValue().repeatNanos).reversed());
        int rank = 0;
        for (Map.Entry<SpriteContents, SpriteStats> entry : sorted) {
            SpriteStats stats = entry.getValue();
            if (stats.repeatNanos <= 0L || ++rank > 25) break;
            LOGGER.info("BOOTOPTIM_GENERATED_ITEM_RESIDUAL_TOP rank={} sprite={} width={} height={} calls={} repeat_calls={} first_ms={} repeat_ms={} avg_repeat_us={} spans_total={} max_us={}",
                    rank, stats.name, stats.width, stats.height, stats.calls, Math.max(0L, stats.calls - 1L), ms(stats.firstNanos), ms(stats.repeatNanos),
                    String.format(Locale.ROOT, "%.3f", stats.calls <= 1L ? 0.0D : stats.repeatNanos / 1_000.0D / (stats.calls - 1L)),
                    stats.spanCountTotal, String.format(Locale.ROOT, "%.3f", stats.maxNanos / 1_000.0D));
        }

        GENERATE_STARTS.remove();
        PROCESS_STARTS.remove();
        SPAN_STARTS.remove();
        SPRITES.clear();
        GENERATED_ELEMENTS.clear();
    }

    private static synchronized void corrupt() { corruptFrames++; }
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D); }
    private static String percent(long part, long total) { return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total); }

    private record SpanFrame(SpriteContents sprite, long startedNanos, boolean first) {}

    private static final class SpriteStats {
        final String name;
        final int width;
        final int height;
        long calls;
        long firstNanos;
        long repeatNanos;
        long maxNanos;
        long spanCountTotal;
        SpriteStats(String name, int width, int height) {
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }
}
