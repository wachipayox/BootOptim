package dev.wachipayox.bootoptim.optimization.client;

import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ExtraFaceData;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage A only: builds a detached immutable representation of strict vanilla ElementsModel geometry,
 * lowers it with the current reload's material/sprite getter and stock BlockModel.bakeFace, compares
 * against the real stock ElementsModel writes, and never substitutes candidate output.
 */
public final class ModelPreparationPlanVerifier {
    public static final String PROPERTY = "boot_optim.modelPreparationPlan";
    public static final String MARKER = "BOOTOPTIM_MODEL_PREPARATION_PLAN";

    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelPreparationPlan");
    private static final AtomicReference<RunStats> CURRENT = new AtomicReference<>();
    private static final ThreadLocal<Deque<VerificationState>> ACTIVE = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicInteger LOGGED_MISMATCHES = new AtomicInteger();
    private static final java.lang.management.ThreadMXBean CPU_BEAN = ManagementFactory.getThreadMXBean();

    private ModelPreparationPlanVerifier() {}

    public static boolean enabled() {
        return "verify".equalsIgnoreCase(System.getProperty(PROPERTY, "off").trim());
    }

    public static void beginReload() {
        if (!enabled()) return;
        CURRENT.set(new RunStats(System.nanoTime(), gcMillis()));
        LOGGED_MISMATCHES.set(0);
    }

    public static long barrierStart() {
        return enabled() ? System.nanoTime() : -1L;
    }

    public static void finishReload(long barrierStartedNanos, Throwable failure) {
        if (!enabled()) return;
        RunStats run = CURRENT.getAndSet(null);
        if (run == null) return;
        run.modelManagerBarrierNanos = barrierStartedNanos > 0L ? System.nanoTime() - barrierStartedNanos : -1L;
        run.gcDeltaMillis = Math.max(0L, gcMillis() - run.gcStartMillis);
        LOGGER.info(
                "{} mode=verify status={} encountered_calls={} eligible_calls={} plans_built={} plan_elements={} plan_faces={} matches={} mismatches={} fallbacks={} "
                        + "plan_build_wall_ms={} plan_build_cpu_ms={} plan_build_alloc_bytes={} candidate_wall_ms={} candidate_cpu_ms={} candidate_alloc_bytes={} "
                        + "candidate_facebakery_ms={} stock_wall_ms={} stock_cpu_ms={} stock_alloc_bytes={} stock_facebakery_ms={} compare_ms={} "
                        + "modelmanager_barrier_ms={} verifier_window_ms={} gc_delta_ms={} ineligible_context={} ineligible_custom_geometry={} ineligible_transform={} "
                        + "ineligible_render_type={} ineligible_owner={} ineligible_parent={} ineligible_elements_identity={} ineligible_extra_face_data={} ineligible_empty={} ineligible_malformed={}",
                MARKER,
                failure == null ? "complete" : "stock_failed",
                run.encountered.sum(), run.eligible.sum(), run.plansBuilt.sum(), run.planElements.sum(), run.planFaces.sum(),
                run.matches.sum(), run.mismatches.sum(), run.fallbacks.sum(),
                ms(run.planBuildWall.sum()), ms(run.planBuildCpu.sum()), run.planBuildAlloc.sum(),
                ms(run.candidateWall.sum()), ms(run.candidateCpu.sum()), run.candidateAlloc.sum(), ms(run.candidateFaceBakery.sum()),
                ms(run.stockWall.sum()), ms(run.stockCpu.sum()), run.stockAlloc.sum(), ms(run.stockFaceBakery.sum()), ms(run.compareWall.sum()),
                ms(run.modelManagerBarrierNanos), ms(System.nanoTime() - run.startedNanos), run.gcDeltaMillis,
                run.ineligibleContext.sum(), run.ineligibleCustomGeometry.sum(), run.ineligibleTransform.sum(), run.ineligibleRenderType.sum(),
                run.ineligibleOwner.sum(), run.ineligibleParent.sum(), run.ineligibleElementsIdentity.sum(), run.ineligibleExtraFaceData.sum(),
                run.ineligibleEmpty.sum(), run.ineligibleMalformed.sum());
    }

    public static void beginElements(
            IGeometryBakingContext context,
            List<BlockElement> elements,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState) {
        RunStats run = CURRENT.get();
        if (!enabled() || run == null) return;
        run.encountered.increment();

        Eligibility eligibility = classify(context, elements, run);
        if (eligibility == null) return;

        BlockGeometryBakingContext blockContext = eligibility.context;
        ModelPreparationPlanHolder holder = (ModelPreparationPlanHolder) blockContext.owner;
        if (holder.bootoptim$modelPreparationPlanPoisoned()) {
            run.fallbacks.increment();
            return;
        }

        Plan plan = holder.bootoptim$modelPreparationPlan();
        if (!holder.bootoptim$modelPreparationPlanCompiled()) {
            long wallStart = System.nanoTime();
            long cpuStart = cpuNanos();
            long allocStart = allocatedBytes();
            try {
                plan = Plan.build(elements);
                holder.bootoptim$setModelPreparationPlan(plan);
                holder.bootoptim$markModelPreparationPlanCompiled();
                if (plan != null) {
                    run.plansBuilt.increment();
                    run.planElements.add(plan.elements.size());
                    run.planFaces.add(plan.faceCount);
                }
            } catch (RuntimeException | LinkageError ex) {
                holder.bootoptim$markModelPreparationPlanCompiled();
                run.fallbacks.increment();
                run.ineligibleMalformed.increment();
                return;
            } finally {
                run.planBuildWall.add(System.nanoTime() - wallStart);
                addDelta(run.planBuildCpu, cpuStart, cpuNanos());
                addDelta(run.planBuildAlloc, allocStart, allocatedBytes());
            }
        }
        if (plan == null) return;

        long candidateWallStart = System.nanoTime();
        long candidateCpuStart = cpuNanos();
        long candidateAllocStart = allocatedBytes();
        List<QuadRecord> candidate;
        try {
            candidate = plan.lower(blockContext, spriteGetter, modelState, run);
        } catch (RuntimeException | LinkageError ex) {
            run.fallbacks.increment();
            holder.bootoptim$poisonModelPreparationPlan();
            return;
        } finally {
            run.candidateWall.add(System.nanoTime() - candidateWallStart);
            addDelta(run.candidateCpu, candidateCpuStart, cpuNanos());
            addDelta(run.candidateAlloc, candidateAllocStart, allocatedBytes());
        }

        run.eligible.increment();
        ACTIVE.get().push(new VerificationState(
                holder,
                context.getModelName(),
                candidate,
                new ArrayList<>(candidate.size()),
                System.nanoTime(),
                cpuNanos(),
                allocatedBytes()));
    }

    /** Redirect target for the stock BlockModel.bakeFace call; returns the exact stock result unchanged. */
    public static BakedQuad stockBakeFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState modelState) {
        VerificationState state = currentState();
        if (state == null) return BlockModel.bakeFace(element, face, sprite, direction, modelState);
        RunStats run = CURRENT.get();
        long started = System.nanoTime();
        try {
            return BlockModel.bakeFace(element, face, sprite, direction, modelState);
        } finally {
            if (run != null) run.stockFaceBakery.add(System.nanoTime() - started);
        }
    }

    public static void recordStockUnculled(BakedQuad quad) {
        VerificationState state = currentState();
        if (state != null) state.stock.add(new QuadRecord(null, quad));
    }

    public static void recordStockCulled(Direction direction, BakedQuad quad) {
        VerificationState state = currentState();
        if (state != null) state.stock.add(new QuadRecord(direction, quad));
    }

    public static void endElements() {
        Deque<VerificationState> stack = ACTIVE.get();
        if (stack.isEmpty()) return;
        VerificationState state = stack.pop();
        if (stack.isEmpty()) ACTIVE.remove();
        RunStats run = CURRENT.get();
        if (run == null) return;

        run.stockWall.add(System.nanoTime() - state.stockWallStart);
        addDelta(run.stockCpu, state.stockCpuStart, cpuNanos());
        addDelta(run.stockAlloc, state.stockAllocStart, allocatedBytes());

        long compareStart = System.nanoTime();
        String mismatch = compare(state.stock, state.candidate);
        run.compareWall.add(System.nanoTime() - compareStart);
        if (mismatch == null) {
            run.matches.increment();
        } else {
            run.mismatches.increment();
            state.holder.bootoptim$poisonModelPreparationPlan();
            if (LOGGED_MISMATCHES.getAndIncrement() < 16) {
                LOGGER.warn("{} mismatch model={} reason={}", MARKER, state.modelName, mismatch);
            }
        }
    }

    private static VerificationState currentState() {
        Deque<VerificationState> stack = ACTIVE.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    private static Eligibility classify(IGeometryBakingContext context, List<BlockElement> elements, RunStats run) {
        if (context == null || context.getClass() != BlockGeometryBakingContext.class) {
            run.ineligibleContext.increment();
            return null;
        }
        BlockGeometryBakingContext blockContext = (BlockGeometryBakingContext) context;
        BlockModel owner = blockContext.owner;
        if (owner == null || owner.getClass() != BlockModel.class || !(owner instanceof ModelPreparationPlanHolder)) {
            run.ineligibleOwner.increment();
            return null;
        }
        try {
            if (blockContext.hasCustomGeometry()) {
                run.ineligibleCustomGeometry.increment();
                return null;
            }
            if (!blockContext.getRootTransform().isIdentity()) {
                run.ineligibleTransform.increment();
                return null;
            }
            if (blockContext.getRenderTypeHint() != null) {
                run.ineligibleRenderType.increment();
                return null;
            }
            if (owner.getElements() != elements) {
                run.ineligibleElementsIdentity.increment();
                return null;
            }
        } catch (RuntimeException ex) {
            run.ineligibleMalformed.increment();
            return null;
        }

        IdentityHashMap<BlockModel, Boolean> seen = new IdentityHashMap<>();
        for (BlockModel cursor = owner; cursor != null; cursor = cursor.parent) {
            if (cursor.getClass() != BlockModel.class || seen.put(cursor, Boolean.TRUE) != null) {
                run.ineligibleParent.increment();
                return null;
            }
        }
        if (elements == null || elements.isEmpty()) {
            run.ineligibleEmpty.increment();
            return null;
        }
        for (BlockElement element : elements) {
            if (element == null || element.faces == null || element.faces.isEmpty()) {
                run.ineligibleMalformed.increment();
                return null;
            }
            if (!ExtraFaceData.DEFAULT.equals(element.getFaceData())) {
                run.ineligibleExtraFaceData.increment();
                return null;
            }
            for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
                BlockElementFace face = entry.getValue();
                if (entry.getKey() == null || face == null || face.uv() == null || face.uv().uvs == null || face.uv().uvs.length != 4
                        || !ExtraFaceData.DEFAULT.equals(face.faceData())) {
                    run.ineligibleExtraFaceData.increment();
                    return null;
                }
            }
        }
        return new Eligibility(blockContext);
    }

    private static String compare(List<QuadRecord> stock, List<QuadRecord> candidate) {
        if (stock.size() != candidate.size()) return "quad_count stock=" + stock.size() + " candidate=" + candidate.size();
        for (int i = 0; i < stock.size(); i++) {
            QuadRecord a = stock.get(i);
            QuadRecord b = candidate.get(i);
            if (a.cullDirection != b.cullDirection) return "cull_bucket@" + i;
            BakedQuad aq = a.quad;
            BakedQuad bq = b.quad;
            if (aq.getClass() != bq.getClass()) return "quad_class@" + i;
            if (aq.getSprite() != bq.getSprite()) return "sprite_identity@" + i;
            if (aq.getTintIndex() != bq.getTintIndex()) return "tint@" + i;
            if (aq.getDirection() != bq.getDirection()) return "direction@" + i;
            if (aq.isShade() != bq.isShade()) return "shade@" + i;
            if (aq.hasAmbientOcclusion() != bq.hasAmbientOcclusion()) return "ambient_occlusion@" + i;
            if (!Arrays.equals(aq.getVertices(), bq.getVertices())) return "vertices@" + i;
        }
        return null;
    }

    private static long cpuNanos() {
        return CPU_BEAN.isCurrentThreadCpuTimeSupported() ? CPU_BEAN.getCurrentThreadCpuTime() : -1L;
    }

    private static long allocatedBytes() {
        return AllocationSupport.currentThreadBytes();
    }

    private static void addDelta(LongAdder target, long before, long after) {
        if (before >= 0L && after >= before) target.add(after - before);
    }

    private static long gcMillis() {
        long sum = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = gc.getCollectionTime();
            if (value > 0L) sum += value;
        }
        return sum;
    }

    private static String ms(long nanos) {
        return nanos < 0L ? "n/a" : String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static final class AllocationSupport {
        private static final ThreadMXBean BEAN = create();

        private static ThreadMXBean create() {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof ThreadMXBean sun) || !sun.isThreadAllocatedMemorySupported()) return null;
            if (!sun.isThreadAllocatedMemoryEnabled()) sun.setThreadAllocatedMemoryEnabled(true);
            return sun;
        }

        private static long currentThreadBytes() {
            ThreadMXBean bean = BEAN;
            return bean == null ? -1L : bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
    }

    private record Eligibility(BlockGeometryBakingContext context) {}

    private static final class VerificationState {
        private final ModelPreparationPlanHolder holder;
        private final String modelName;
        private final List<QuadRecord> candidate;
        private final List<QuadRecord> stock;
        private final long stockWallStart;
        private final long stockCpuStart;
        private final long stockAllocStart;

        private VerificationState(ModelPreparationPlanHolder holder, String modelName, List<QuadRecord> candidate,
                List<QuadRecord> stock, long stockWallStart, long stockCpuStart, long stockAllocStart) {
            this.holder = holder;
            this.modelName = modelName;
            this.candidate = candidate;
            this.stock = stock;
            this.stockWallStart = stockWallStart;
            this.stockCpuStart = stockCpuStart;
            this.stockAllocStart = stockAllocStart;
        }
    }

    private record QuadRecord(Direction cullDirection, BakedQuad quad) {}

    public static final class Plan {
        private final List<ElementIr> elements;
        private final int faceCount;

        private Plan(List<ElementIr> elements, int faceCount) {
            this.elements = List.copyOf(elements);
            this.faceCount = faceCount;
        }

        private static Plan build(List<BlockElement> source) {
            ArrayList<ElementIr> elements = new ArrayList<>(source.size());
            int faceCount = 0;
            for (BlockElement element : source) {
                ArrayList<FaceIr> faces = new ArrayList<>(element.faces.size());
                for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
                    BlockElementFace face = entry.getValue();
                    float[] uv = face.uv().uvs;
                    faces.add(new FaceIr(entry.getKey(), face.cullForDirection(), face.tintIndex(), face.texture(),
                            Float.floatToRawIntBits(uv[0]), Float.floatToRawIntBits(uv[1]),
                            Float.floatToRawIntBits(uv[2]), Float.floatToRawIntBits(uv[3]), face.uv().rotation));
                    faceCount++;
                }
                RotationIr rotation = null;
                if (element.rotation != null) {
                    rotation = new RotationIr(Vec3Bits.of(element.rotation.origin), element.rotation.axis,
                            Float.floatToRawIntBits(element.rotation.angle), element.rotation.rescale);
                }
                elements.add(new ElementIr(Vec3Bits.of(element.from), Vec3Bits.of(element.to), rotation, element.shade, List.copyOf(faces)));
            }
            return faceCount == 0 ? null : new Plan(elements, faceCount);
        }

        private List<QuadRecord> lower(BlockGeometryBakingContext context,
                Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, RunStats run) {
            ArrayList<QuadRecord> result = new ArrayList<>(faceCount);
            for (ElementIr elementIr : elements) {
                LinkedHashMap<Direction, BlockElementFace> faces = new LinkedHashMap<>(elementIr.faces.size());
                for (FaceIr faceIr : elementIr.faces) {
                    BlockFaceUV uv = new BlockFaceUV(new float[] {
                            Float.intBitsToFloat(faceIr.u0), Float.intBitsToFloat(faceIr.v0),
                            Float.intBitsToFloat(faceIr.u1), Float.intBitsToFloat(faceIr.v1)
                    }, faceIr.uvRotation);
                    faces.put(faceIr.direction, new BlockElementFace(faceIr.cullDirection, faceIr.tintIndex, faceIr.texture, uv));
                }
                BlockElementRotation rotation = elementIr.rotation == null ? null : elementIr.rotation.materialize();
                BlockElement element = new BlockElement(elementIr.from.materialize(), elementIr.to.materialize(), faces, rotation, elementIr.shade);
                for (FaceIr faceIr : elementIr.faces) {
                    BlockElementFace face = faces.get(faceIr.direction);
                    TextureAtlasSprite sprite = spriteGetter.apply(context.getMaterial(faceIr.texture));
                    long faceStart = System.nanoTime();
                    BakedQuad quad;
                    try {
                        quad = BlockModel.bakeFace(element, face, sprite, faceIr.direction, modelState);
                    } finally {
                        run.candidateFaceBakery.add(System.nanoTime() - faceStart);
                    }
                    Direction bucket = faceIr.cullDirection == null ? null : modelState.getRotation().rotateTransform(faceIr.cullDirection);
                    result.add(new QuadRecord(bucket, quad));
                }
            }
            return List.copyOf(result);
        }
    }

    private record ElementIr(Vec3Bits from, Vec3Bits to, RotationIr rotation, boolean shade, List<FaceIr> faces) {}

    private record FaceIr(Direction direction, Direction cullDirection, int tintIndex, String texture,
            int u0, int v0, int u1, int v1, int uvRotation) {}

    private record RotationIr(Vec3Bits origin, Direction.Axis axis, int angleBits, boolean rescale) {
        private BlockElementRotation materialize() {
            return new BlockElementRotation(origin.materialize(), axis, Float.intBitsToFloat(angleBits), rescale);
        }
    }

    private record Vec3Bits(int x, int y, int z) {
        private static Vec3Bits of(Vector3f value) {
            return new Vec3Bits(Float.floatToRawIntBits(value.x()), Float.floatToRawIntBits(value.y()), Float.floatToRawIntBits(value.z()));
        }

        private Vector3f materialize() {
            return new Vector3f(Float.intBitsToFloat(x), Float.intBitsToFloat(y), Float.intBitsToFloat(z));
        }
    }

    private static final class RunStats {
        private final long startedNanos;
        private final long gcStartMillis;
        private volatile long gcDeltaMillis;
        private volatile long modelManagerBarrierNanos = -1L;
        private final LongAdder encountered = new LongAdder();
        private final LongAdder eligible = new LongAdder();
        private final LongAdder plansBuilt = new LongAdder();
        private final LongAdder planElements = new LongAdder();
        private final LongAdder planFaces = new LongAdder();
        private final LongAdder matches = new LongAdder();
        private final LongAdder mismatches = new LongAdder();
        private final LongAdder fallbacks = new LongAdder();
        private final LongAdder planBuildWall = new LongAdder();
        private final LongAdder planBuildCpu = new LongAdder();
        private final LongAdder planBuildAlloc = new LongAdder();
        private final LongAdder candidateWall = new LongAdder();
        private final LongAdder candidateCpu = new LongAdder();
        private final LongAdder candidateAlloc = new LongAdder();
        private final LongAdder candidateFaceBakery = new LongAdder();
        private final LongAdder stockWall = new LongAdder();
        private final LongAdder stockCpu = new LongAdder();
        private final LongAdder stockAlloc = new LongAdder();
        private final LongAdder stockFaceBakery = new LongAdder();
        private final LongAdder compareWall = new LongAdder();
        private final LongAdder ineligibleContext = new LongAdder();
        private final LongAdder ineligibleCustomGeometry = new LongAdder();
        private final LongAdder ineligibleTransform = new LongAdder();
        private final LongAdder ineligibleRenderType = new LongAdder();
        private final LongAdder ineligibleOwner = new LongAdder();
        private final LongAdder ineligibleParent = new LongAdder();
        private final LongAdder ineligibleElementsIdentity = new LongAdder();
        private final LongAdder ineligibleExtraFaceData = new LongAdder();
        private final LongAdder ineligibleEmpty = new LongAdder();
        private final LongAdder ineligibleMalformed = new LongAdder();

        private RunStats(long startedNanos, long gcStartMillis) {
            this.startedNanos = startedNanos;
            this.gcStartMillis = gcStartMillis;
        }
    }
}
