package dev.wachipayox.bootoptim.profiling.client;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only, low-frequency sampler for the NeoForge ElementsModel hot path.
 *
 * <p>The sampler is deliberately single-threaded/plain-counter based. ModelBakery#bakeModels is
 * currently sequential in the supported 1.21.1 pipeline; if that invariant changes, the profiler
 * reports concurrent/corrupt samples instead of adding synchronization to a multi-million-face path.
 */
public final class CompiledElementsProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/CompiledElementsProfile");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.compiledElementsProfile");
    private static final int SAMPLE_MASK = sampleMask();

    private static boolean active;
    private static Thread ownerThread;
    private static long timerPairOverheadNanos;

    private static long elementsCalls;
    private static long elementsNanos;
    private static long elementsStarted;
    private static long elementMaps;
    private static boolean elementCallSampled;

    private static long faceCalls;
    private static boolean faceSampled;

    private static long rootCalls;
    private static long rootSamples;
    private static long rootNanos;
    private static long rootComposeCalls;
    private static long rootComposeSamples;
    private static long rootComposeNanos;

    private static long materialSamples;
    private static long materialNanos;
    private static long spriteSamples;
    private static long spriteNanos;
    private static long blockModelFaceSamples;
    private static long blockModelFaceNanos;
    private static long faceBakerySamples;
    private static long faceBakeryNanos;
    private static long faceBakeryStarted;
    private static boolean faceBakeryRequested;

    private static long fillNormalSamples;
    private static long fillNormalNanos;
    private static long fillNormalStarted;
    private static long faceDataSamples;
    private static long faceDataNanos;
    private static long faceDataStarted;

    private static long culledFaces;
    private static long cullTransformSamples;
    private static long cullTransformNanos;
    private static long builderSamples;
    private static long builderNanos;

    private static long concurrentOrCorruptSamples;

    private CompiledElementsProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void begin() {
        if (!ENABLED) return;
        reset();
        ownerThread = Thread.currentThread();
        timerPairOverheadNanos = calibrateTimerPair();
        active = true;
    }

    public static void beginElements() {
        if (!active) return;
        if (!onOwnerThread() || elementsStarted != 0L) {
            concurrentOrCorruptSamples++;
            return;
        }
        elementsCalls++;
        elementCallSampled = (elementsCalls & SAMPLE_MASK) == 0L;
        elementsStarted = System.nanoTime();
    }

    public static void endElements() {
        if (!active || elementsStarted == 0L) return;
        elementsNanos += System.nanoTime() - elementsStarted;
        elementsStarted = 0L;
        elementCallSampled = false;
        faceSampled = false;
        faceBakeryRequested = false;
    }

    public static void elementMap() {
        if (active && onOwnerThread()) elementMaps++;
    }

    public static long startRoot() {
        if (!active || !elementCallSampled || !onOwnerThread()) return 0L;
        rootCalls++;
        rootSamples++;
        return System.nanoTime();
    }

    public static void endRoot(long started) {
        if (started != 0L) rootNanos += System.nanoTime() - started;
    }

    public static long startRootCompose() {
        if (!active || !elementCallSampled || !onOwnerThread()) return 0L;
        rootComposeCalls++;
        rootComposeSamples++;
        return System.nanoTime();
    }

    public static void rootComposeObserved() {
        if (active && onOwnerThread() && !elementCallSampled) rootComposeCalls++;
    }

    public static void endRootCompose(long started) {
        if (started != 0L) rootComposeNanos += System.nanoTime() - started;
    }

    /** Starts one logical face and returns whether this face should be timed. */
    public static boolean beginFace() {
        if (!active || !onOwnerThread()) return false;
        faceCalls++;
        faceSampled = (faceCalls & SAMPLE_MASK) == 0L;
        return faceSampled;
    }

    public static boolean faceSampled() {
        return active && faceSampled && onOwnerThread();
    }

    public static long startMaterial() {
        if (!faceSampled()) return 0L;
        materialSamples++;
        return System.nanoTime();
    }

    public static void endMaterial(long started) {
        if (started != 0L) materialNanos += System.nanoTime() - started;
    }

    public static long startSprite() {
        if (!faceSampled()) return 0L;
        spriteSamples++;
        return System.nanoTime();
    }

    public static void endSprite(long started) {
        if (started != 0L) spriteNanos += System.nanoTime() - started;
    }

    public static long startBlockModelFace() {
        if (!faceSampled()) return 0L;
        blockModelFaceSamples++;
        faceBakeryRequested = true;
        return System.nanoTime();
    }

    public static void endBlockModelFace(long started) {
        if (started != 0L) blockModelFaceNanos += System.nanoTime() - started;
        faceBakeryRequested = false;
    }

    public static void beginFaceBakery() {
        if (!active || !faceBakeryRequested || !onOwnerThread()) return;
        if (faceBakeryStarted != 0L) {
            concurrentOrCorruptSamples++;
            return;
        }
        faceBakerySamples++;
        faceBakeryStarted = System.nanoTime();
    }

    public static void endFaceBakery() {
        if (!active || faceBakeryStarted == 0L) return;
        faceBakeryNanos += System.nanoTime() - faceBakeryStarted;
        faceBakeryStarted = 0L;
    }

    public static void beginFillNormal() {
        if (!active || faceBakeryStarted == 0L || !onOwnerThread()) return;
        fillNormalSamples++;
        fillNormalStarted = System.nanoTime();
    }

    public static void endFillNormal() {
        if (fillNormalStarted == 0L) return;
        fillNormalNanos += System.nanoTime() - fillNormalStarted;
        fillNormalStarted = 0L;
    }

    public static void beginFaceData() {
        if (!active || faceBakeryStarted == 0L || !onOwnerThread()) return;
        faceDataSamples++;
        faceDataStarted = System.nanoTime();
    }

    public static void endFaceData() {
        if (faceDataStarted == 0L) return;
        faceDataNanos += System.nanoTime() - faceDataStarted;
        faceDataStarted = 0L;
    }

    public static long startCullTransform() {
        if (!active || !onOwnerThread()) return 0L;
        culledFaces++;
        if (!faceSampled) return 0L;
        cullTransformSamples++;
        return System.nanoTime();
    }

    public static void endCullTransform(long started) {
        if (started != 0L) cullTransformNanos += System.nanoTime() - started;
    }

    public static long startBuilder() {
        if (!faceSampled()) return 0L;
        builderSamples++;
        return System.nanoTime();
    }

    public static void endBuilder(long started) {
        if (started != 0L) builderNanos += System.nanoTime() - started;
        faceSampled = false;
    }

    public static void finish() {
        if (!active) return;
        active = false;

        long correctedElements = corrected(elementsNanos, elementsCalls);
        double materialPer = perSample(materialNanos, materialSamples);
        double spritePer = perSample(spriteNanos, spriteSamples);
        double blockFacePer = perSample(blockModelFaceNanos, blockModelFaceSamples);
        double faceBakeryPer = perSample(faceBakeryNanos, faceBakerySamples);
        double cullPer = perSample(cullTransformNanos, cullTransformSamples);
        double builderPer = perSample(builderNanos, builderSamples);

        long materialEstimated = estimate(materialPer, faceCalls);
        long spriteEstimated = estimate(spritePer, faceCalls);
        long blockFaceEstimated = estimate(blockFacePer, faceCalls);
        long faceBakeryEstimated = estimate(faceBakeryPer, faceCalls);
        long cullEstimated = estimate(cullPer, culledFaces);
        long builderEstimated = estimate(builderPer, faceCalls);
        long rootEstimated = estimate(perSample(rootNanos, rootSamples), elementsCalls);
        long rootComposeEstimated = rootComposeCalls == 0L ? 0L : estimate(perSample(rootComposeNanos, rootComposeSamples), rootComposeCalls);

        long structuralResidual = Math.max(0L, correctedElements
                - materialEstimated
                - spriteEstimated
                - blockFaceEstimated
                - cullEstimated
                - builderEstimated
                - rootEstimated
                - rootComposeEstimated);
        long wrapperEstimated = Math.max(0L, blockFaceEstimated - faceBakeryEstimated);
        long preFaceInclusive = Math.max(0L, correctedElements - faceBakeryEstimated - cullEstimated - builderEstimated);

        LOGGER.info(
                "BOOTOPTIM_COMPILED_ELEMENTS_PROFILE sample_rate={} timer_pair_ns={} elements_calls={} elements_total_ms={} element_maps={} faces={} culled_faces={} material_samples={} material_est_ms={} sprite_samples={} sprite_est_ms={} blockmodel_face_samples={} blockmodel_face_est_ms={} facebakery_samples={} facebakery_est_ms={} bakeface_wrapper_est_ms={} cull_samples={} cull_est_ms={} builder_samples={} builder_est_ms={} root_samples={} root_est_ms={} root_compose_calls={} root_compose_samples={} root_compose_est_ms={} structural_residual_est_ms={} pre_face_inclusive_est_ms={} fill_normal_samples={} fill_normal_sample_ms={} face_data_samples={} face_data_sample_ms={} corrupt_samples={}",
                SAMPLE_MASK + 1,
                timerPairOverheadNanos,
                elementsCalls,
                ms(correctedElements),
                elementMaps,
                faceCalls,
                culledFaces,
                materialSamples,
                ms(materialEstimated),
                spriteSamples,
                ms(spriteEstimated),
                blockModelFaceSamples,
                ms(blockFaceEstimated),
                faceBakerySamples,
                ms(faceBakeryEstimated),
                ms(wrapperEstimated),
                cullTransformSamples,
                ms(cullEstimated),
                builderSamples,
                ms(builderEstimated),
                rootSamples,
                ms(rootEstimated),
                rootComposeCalls,
                rootComposeSamples,
                ms(rootComposeEstimated),
                ms(structuralResidual),
                ms(preFaceInclusive),
                fillNormalSamples,
                ms(corrected(fillNormalNanos, fillNormalSamples)),
                faceDataSamples,
                ms(corrected(faceDataNanos, faceDataSamples)),
                concurrentOrCorruptSamples);

        reset();
    }

    private static boolean onOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    private static long corrected(long nanos, long samples) {
        return Math.max(0L, nanos - timerPairOverheadNanos * samples);
    }

    private static double perSample(long nanos, long samples) {
        return samples == 0L ? 0.0D : corrected(nanos, samples) / (double) samples;
    }

    private static long estimate(double perCallNanos, long calls) {
        if (perCallNanos <= 0.0D || calls <= 0L) return 0L;
        double result = perCallNanos * calls;
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(result);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static long calibrateTimerPair() {
        final int iterations = 16_384;
        long total = 0L;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            total += System.nanoTime() - start;
        }
        return Math.max(0L, total / iterations);
    }

    private static int sampleMask() {
        int requested = Integer.getInteger("boot_optim.compiledElementsProfileSampleRate", 64);
        int rate = 1;
        while (rate < requested && rate < 1 << 20) rate <<= 1;
        return Math.max(1, rate) - 1;
    }

    private static void reset() {
        elementsCalls = elementsNanos = elementsStarted = elementMaps = 0L;
        faceCalls = 0L;
        rootCalls = rootSamples = rootNanos = rootComposeCalls = rootComposeSamples = rootComposeNanos = 0L;
        materialSamples = materialNanos = spriteSamples = spriteNanos = 0L;
        blockModelFaceSamples = blockModelFaceNanos = faceBakerySamples = faceBakeryNanos = faceBakeryStarted = 0L;
        fillNormalSamples = fillNormalNanos = fillNormalStarted = faceDataSamples = faceDataNanos = faceDataStarted = 0L;
        culledFaces = cullTransformSamples = cullTransformNanos = builderSamples = builderNanos = 0L;
        concurrentOrCorruptSamples = 0L;
        elementCallSampled = faceSampled = faceBakeryRequested = false;
        ownerThread = null;
    }
}
