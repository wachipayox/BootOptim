package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Diagnostic-only split of vanilla-elements and generated-item quad baking. */
public final class ModelElementResidualProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelElementsResidual");
    private static final ThreadLocal<Deque<Frame>> ELEMENTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<Long>> GENERATED_FACES = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile boolean active;

    private static long elementsCalls;
    private static long elementsNanos;
    private static long elementsFaceCalls;
    private static long elementsFaceNanos;
    private static long generatedFaceCalls;
    private static long generatedFaceNanos;
    private static long corruptFrames;

    private ModelElementResidualProfiler() {}

    public static synchronized void begin() {
        elementsCalls = elementsNanos = elementsFaceCalls = elementsFaceNanos = 0L;
        generatedFaceCalls = generatedFaceNanos = 0L;
        corruptFrames = 0L;
        ELEMENTS.remove();
        GENERATED_FACES.remove();
        active = true;
    }

    public static void beginElements() {
        if (active) ELEMENTS.get().push(new Frame(System.nanoTime()));
    }

    public static void endElements() {
        if (!active) return;
        Frame frame = ELEMENTS.get().poll();
        if (frame == null) { corrupt(); return; }
        synchronized (ModelElementResidualProfiler.class) {
            elementsCalls++;
            elementsNanos += System.nanoTime() - frame.startedNanos;
        }
    }

    public static void beginGeneratedFace(BlockElement element) {
        if (active && GeneratedItemResidualProfiler.isGeneratedElement(element)) {
            GENERATED_FACES.get().push(System.nanoTime());
        } else {
            GENERATED_FACES.get().push(-1L);
        }
    }

    public static void endGeneratedFace() {
        if (!active) return;
        Long started = GENERATED_FACES.get().poll();
        if (started == null) { corrupt(); return; }
        if (started < 0L) return;
        synchronized (ModelElementResidualProfiler.class) {
            generatedFaceCalls++;
            generatedFaceNanos += System.nanoTime() - started;
        }
    }

    public static BakedQuad profileElementsFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state) {
        if (!active) return BlockModel.bakeFace(element, face, sprite, direction, state);
        long started = System.nanoTime();
        try {
            return BlockModel.bakeFace(element, face, sprite, direction, state);
        } finally {
            synchronized (ModelElementResidualProfiler.class) {
                elementsFaceCalls++;
                elementsFaceNanos += System.nanoTime() - started;
            }
        }
    }

    public static synchronized void finish() {
        if (!active) return;
        active = false;
        LOGGER.info("BOOTOPTIM_MODEL_ELEMENTS_RESIDUAL elements_calls={} elements_total_ms={} elements_face_calls={} elements_face_ms={} elements_non_face_ms={} elements_face_share_percent={} generated_face_calls={} generated_face_ms={} corrupt_frames={}",
                elementsCalls,
                ms(elementsNanos),
                elementsFaceCalls,
                ms(elementsFaceNanos),
                ms(Math.max(0L, elementsNanos - elementsFaceNanos)),
                percent(elementsFaceNanos, elementsNanos),
                generatedFaceCalls,
                ms(generatedFaceNanos),
                corruptFrames);
        ELEMENTS.remove();
        GENERATED_FACES.remove();
    }

    private static synchronized void corrupt() { corruptFrames++; }
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D); }
    private static String percent(long part, long total) { return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total); }
    private record Frame(long startedNanos) {}
}
