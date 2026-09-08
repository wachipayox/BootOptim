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

/** Stage A shadow verifier: candidate output is never published. */
public final class ModelPreparationPlanVerifier {
    public static final String PROPERTY = "boot_optim.modelPreparationPlan";
    public static final String MARKER = "BOOTOPTIM_MODEL_PREPARATION_PLAN";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelPreparationPlan");
    private static final AtomicReference<RunStats> CURRENT = new AtomicReference<>();
    private static final ThreadLocal<Deque<State>> ACTIVE = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final java.lang.management.ThreadMXBean CPU = ManagementFactory.getThreadMXBean();

    private ModelPreparationPlanVerifier() {}

    public static boolean enabled() {
        return "verify".equalsIgnoreCase(System.getProperty(PROPERTY, "off").trim());
    }

    public static void beginReload() {
        if (enabled()) {
            CURRENT.set(new RunStats(System.nanoTime(), gcMillis()));
            LOGGED.set(0);
        }
    }

    public static long barrierStart() {
        return enabled() ? System.nanoTime() : -1L;
    }

    public static void finishReload(long started, Throwable failure) {
        RunStats r = CURRENT.getAndSet(null);
        if (r == null) return;
        long barrier = started > 0 ? System.nanoTime() - started : -1;
        long gc = Math.max(0, gcMillis() - r.gcStart);
        LOGGER.info("{} mode=verify status={} encountered_calls={} eligible_calls={} plans_built={} plan_elements={} plan_faces={} matches={} mismatches={} fallbacks={} plan_build_wall_ms={} plan_build_cpu_ms={} plan_build_alloc_bytes={} candidate_wall_ms={} candidate_cpu_ms={} candidate_alloc_bytes={} candidate_facebakery_ms={} stock_wall_ms={} stock_cpu_ms={} stock_alloc_bytes={} stock_facebakery_ms={} compare_ms={} modelmanager_barrier_ms={} verifier_window_ms={} gc_delta_ms={} ineligible_context={} ineligible_custom_geometry={} ineligible_transform={} ineligible_render_type={} ineligible_owner={} ineligible_parent={} ineligible_elements_identity={} ineligible_extra_face_data={} ineligible_empty={} ineligible_malformed={}",
                MARKER, failure == null ? "complete" : "stock_failed", r.encountered.sum(), r.eligible.sum(), r.plans.sum(), r.elements.sum(), r.faces.sum(), r.matches.sum(), r.mismatches.sum(), r.fallbacks.sum(),
                ms(r.planWall.sum()), ms(r.planCpu.sum()), r.planAlloc.sum(), ms(r.candidateWall.sum()), ms(r.candidateCpu.sum()), r.candidateAlloc.sum(), ms(r.candidateFace.sum()),
                ms(r.stockWall.sum()), ms(r.stockCpu.sum()), r.stockAlloc.sum(), ms(r.stockFace.sum()), ms(r.compare.sum()), ms(barrier), ms(System.nanoTime() - r.started), gc,
                r.badContext.sum(), r.badCustom.sum(), r.badTransform.sum(), r.badRender.sum(), r.badOwner.sum(), r.badParent.sum(), r.badIdentity.sum(), r.badFaceData.sum(), r.badEmpty.sum(), r.badMalformed.sum());
    }

    public static void beginElements(IGeometryBakingContext context, List<BlockElement> elements, ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter, ModelState state) {
        RunStats r = CURRENT.get();
        if (!enabled() || r == null) return;
        r.encountered.increment();
        BlockGeometryBakingContext ctx = classify(context, elements, r);
        if (ctx == null) return;
        ModelPreparationPlanHolder holder = (ModelPreparationPlanHolder) ctx.owner;
        if (holder.bootoptim$modelPreparationPlanPoisoned()) { r.fallbacks.increment(); return; }

        Plan plan = holder.bootoptim$modelPreparationPlan();
        if (!holder.bootoptim$modelPreparationPlanCompiled()) {
            long w = System.nanoTime(), c = cpu(), a = alloc();
            try {
                plan = Plan.build(elements);
                holder.bootoptim$setModelPreparationPlan(plan);
                holder.bootoptim$markModelPreparationPlanCompiled();
                if (plan != null) { r.plans.increment(); r.elements.add(plan.elements.size()); r.faces.add(plan.faceCount); }
            } catch (RuntimeException | LinkageError ex) {
                holder.bootoptim$markModelPreparationPlanCompiled(); r.fallbacks.increment(); r.badMalformed.increment(); return;
            } finally {
                r.planWall.add(System.nanoTime() - w); delta(r.planCpu, c, cpu()); delta(r.planAlloc, a, alloc());
            }
        }
        if (plan == null) return;

        long w = System.nanoTime(), c = cpu(), a = alloc();
        List<QuadRecord> candidate;
        try {
            candidate = plan.lower(ctx, spriteGetter, state, r);
        } catch (RuntimeException | LinkageError ex) {
            holder.bootoptim$poisonModelPreparationPlan(); r.fallbacks.increment(); return;
        } finally {
            r.candidateWall.add(System.nanoTime() - w); delta(r.candidateCpu, c, cpu()); delta(r.candidateAlloc, a, alloc());
        }
        r.eligible.increment();
        ACTIVE.get().push(new State(holder, context.getModelName(), candidate, new ArrayList<>(candidate.size()), System.nanoTime(), cpu(), alloc()));
    }

    public static BakedQuad stockBakeFace(BlockElement element, BlockElementFace face, TextureAtlasSprite sprite,
            Direction direction, ModelState modelState) {
        State state = current();
        if (state == null) return BlockModel.bakeFace(element, face, sprite, direction, modelState);
        RunStats r = CURRENT.get(); long s = System.nanoTime();
        try { return BlockModel.bakeFace(element, face, sprite, direction, modelState); }
        finally { if (r != null) r.stockFace.add(System.nanoTime() - s); }
    }

    public static void recordStockUnculled(BakedQuad quad) { State s = current(); if (s != null) s.stock.add(new QuadRecord(null, quad)); }
    public static void recordStockCulled(Direction direction, BakedQuad quad) { State s = current(); if (s != null) s.stock.add(new QuadRecord(direction, quad)); }

    public static void endElements() {
        Deque<State> stack = ACTIVE.get(); if (stack.isEmpty()) return;
        State s = stack.pop(); if (stack.isEmpty()) ACTIVE.remove();
        RunStats r = CURRENT.get(); if (r == null) return;
        r.stockWall.add(System.nanoTime() - s.wall); delta(r.stockCpu, s.cpu, cpu()); delta(r.stockAlloc, s.alloc, alloc());
        long compareStart = System.nanoTime(); String mismatch = compare(s.stock, s.candidate); r.compare.add(System.nanoTime() - compareStart);
        if (mismatch == null) r.matches.increment();
        else { r.mismatches.increment(); s.holder.bootoptim$poisonModelPreparationPlan(); if (LOGGED.getAndIncrement() < 16) LOGGER.warn("{} mismatch model={} reason={}", MARKER, s.name, mismatch); }
    }

    private static State current() { Deque<State> s = ACTIVE.get(); return s.isEmpty() ? null : s.peek(); }

    private static BlockGeometryBakingContext classify(IGeometryBakingContext context, List<BlockElement> elements, RunStats r) {
        if (context == null || context.getClass() != BlockGeometryBakingContext.class) { r.badContext.increment(); return null; }
        BlockGeometryBakingContext ctx = (BlockGeometryBakingContext) context; BlockModel owner = ctx.owner;
        if (owner == null || owner.getClass() != BlockModel.class || !(owner instanceof ModelPreparationPlanHolder)) { r.badOwner.increment(); return null; }
        try {
            if (ctx.hasCustomGeometry()) { r.badCustom.increment(); return null; }
            if (!ctx.getRootTransform().isIdentity()) { r.badTransform.increment(); return null; }
            if (ctx.getRenderTypeHint() != null) { r.badRender.increment(); return null; }
            if (owner.getElements() != elements) { r.badIdentity.increment(); return null; }
        } catch (RuntimeException ex) { r.badMalformed.increment(); return null; }
        IdentityHashMap<BlockModel, Boolean> seen = new IdentityHashMap<>();
        for (BlockModel cursor = owner; cursor != null; cursor = cursor.parent)
            if (cursor.getClass() != BlockModel.class || seen.put(cursor, Boolean.TRUE) != null) { r.badParent.increment(); return null; }
        if (elements == null || elements.isEmpty()) { r.badEmpty.increment(); return null; }
        for (BlockElement e : elements) {
            if (e == null || e.faces == null || e.faces.isEmpty()) { r.badMalformed.increment(); return null; }
            if (!ExtraFaceData.DEFAULT.equals(e.getFaceData())) { r.badFaceData.increment(); return null; }
            for (Map.Entry<Direction, BlockElementFace> entry : e.faces.entrySet()) {
                BlockElementFace f = entry.getValue();
                if (entry.getKey() == null || f == null || f.uv() == null || f.uv().uvs == null || f.uv().uvs.length != 4 || !ExtraFaceData.DEFAULT.equals(f.faceData())) { r.badFaceData.increment(); return null; }
            }
        }
        return ctx;
    }

    private static String compare(List<QuadRecord> a, List<QuadRecord> b) {
        if (a.size() != b.size()) return "quad_count:" + a.size() + "/" + b.size();
        for (int i = 0; i < a.size(); i++) {
            QuadRecord x = a.get(i), y = b.get(i); BakedQuad q = x.quad, z = y.quad;
            if (x.cull != y.cull) return "cull@" + i;
            if (q.getClass() != z.getClass()) return "class@" + i;
            if (q.getSprite() != z.getSprite()) return "sprite_identity@" + i;
            if (q.getTintIndex() != z.getTintIndex() || q.getDirection() != z.getDirection() || q.isShade() != z.isShade() || q.hasAmbientOcclusion() != z.hasAmbientOcclusion()) return "metadata@" + i;
            if (!Arrays.equals(q.getVertices(), z.getVertices())) return "vertices@" + i;
        }
        return null;
    }

    private static long cpu() { return CPU.isCurrentThreadCpuTimeSupported() ? CPU.getCurrentThreadCpuTime() : -1; }
    private static long alloc() { return Allocation.BEAN == null ? -1 : Allocation.BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static void delta(LongAdder out, long a, long b) { if (a >= 0 && b >= a) out.add(b - a); }
    private static long gcMillis() { long v = 0; for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) if (g.getCollectionTime() > 0) v += g.getCollectionTime(); return v; }
    private static String ms(long n) { return n < 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", n / 1_000_000d); }

    private static final class Allocation {
        static final ThreadMXBean BEAN = make();
        static ThreadMXBean make() { java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean(); if (!(b instanceof ThreadMXBean s) || !s.isThreadAllocatedMemorySupported()) return null; if (!s.isThreadAllocatedMemoryEnabled()) s.setThreadAllocatedMemoryEnabled(true); return s; }
    }

    private record State(ModelPreparationPlanHolder holder, String name, List<QuadRecord> candidate, List<QuadRecord> stock, long wall, long cpu, long alloc) {}
    private record QuadRecord(Direction cull, BakedQuad quad) {}

    public static final class Plan {
        private final List<ElementIr> elements; private final int faceCount;
        private Plan(List<ElementIr> elements, int faceCount) { this.elements = List.copyOf(elements); this.faceCount = faceCount; }
        private static Plan build(List<BlockElement> source) {
            ArrayList<ElementIr> out = new ArrayList<>(source.size()); int count = 0;
            for (BlockElement e : source) {
                ArrayList<FaceIr> faces = new ArrayList<>(e.faces.size());
                for (Map.Entry<Direction, BlockElementFace> entry : e.faces.entrySet()) {
                    BlockElementFace f = entry.getValue(); float[] u = f.uv().uvs;
                    faces.add(new FaceIr(entry.getKey(), f.cullForDirection(), f.tintIndex(), f.texture(), bits(u[0]), bits(u[1]), bits(u[2]), bits(u[3]), f.uv().rotation)); count++;
                }
                RotationIr rot = e.rotation == null ? null : new RotationIr(Vec3Bits.of(e.rotation.origin()), e.rotation.axis(), bits(e.rotation.angle()), e.rotation.rescale());
                out.add(new ElementIr(Vec3Bits.of(e.from), Vec3Bits.of(e.to), rot, e.shade, List.copyOf(faces)));
            }
            return count == 0 ? null : new Plan(out, count);
        }
        private List<QuadRecord> lower(BlockGeometryBakingContext ctx, Function<Material, TextureAtlasSprite> sprites, ModelState state, RunStats r) {
            ArrayList<QuadRecord> out = new ArrayList<>(faceCount);
            for (ElementIr e : elements) {
                LinkedHashMap<Direction, BlockElementFace> map = new LinkedHashMap<>(e.faces.size());
                for (FaceIr f : e.faces) map.put(f.direction, new BlockElementFace(f.cull, f.tint, f.texture, new BlockFaceUV(new float[]{val(f.u0), val(f.v0), val(f.u1), val(f.v1)}, f.uvRotation)));
                BlockElement live = new BlockElement(e.from.make(), e.to.make(), map, e.rotation == null ? null : e.rotation.make(), e.shade);
                for (FaceIr f : e.faces) {
                    BlockElementFace face = map.get(f.direction); TextureAtlasSprite sprite = sprites.apply(ctx.getMaterial(f.texture)); long s = System.nanoTime(); BakedQuad quad;
                    try { quad = BlockModel.bakeFace(live, face, sprite, f.direction, state); } finally { r.candidateFace.add(System.nanoTime() - s); }
                    Direction bucket = f.cull == null ? null : state.getRotation().rotateTransform(f.cull); out.add(new QuadRecord(bucket, quad));
                }
            }
            return List.copyOf(out);
        }
    }

    private record ElementIr(Vec3Bits from, Vec3Bits to, RotationIr rotation, boolean shade, List<FaceIr> faces) {}
    private record FaceIr(Direction direction, Direction cull, int tint, String texture, int u0, int v0, int u1, int v1, int uvRotation) {}
    private record RotationIr(Vec3Bits origin, Direction.Axis axis, int angle, boolean rescale) { BlockElementRotation make() { return new BlockElementRotation(origin.make(), axis, val(angle), rescale); } }
    private record Vec3Bits(int x, int y, int z) { static Vec3Bits of(Vector3f v) { return new Vec3Bits(bits(v.x()), bits(v.y()), bits(v.z())); } Vector3f make() { return new Vector3f(val(x), val(y), val(z)); } }
    private static int bits(float v) { return Float.floatToRawIntBits(v); }
    private static float val(int v) { return Float.intBitsToFloat(v); }

    private static final class RunStats {
        final long started, gcStart; RunStats(long s, long g) { started=s; gcStart=g; }
        final LongAdder encountered=new LongAdder(), eligible=new LongAdder(), plans=new LongAdder(), elements=new LongAdder(), faces=new LongAdder(), matches=new LongAdder(), mismatches=new LongAdder(), fallbacks=new LongAdder();
        final LongAdder planWall=new LongAdder(), planCpu=new LongAdder(), planAlloc=new LongAdder(), candidateWall=new LongAdder(), candidateCpu=new LongAdder(), candidateAlloc=new LongAdder(), candidateFace=new LongAdder(), stockWall=new LongAdder(), stockCpu=new LongAdder(), stockAlloc=new LongAdder(), stockFace=new LongAdder(), compare=new LongAdder();
        final LongAdder badContext=new LongAdder(), badCustom=new LongAdder(), badTransform=new LongAdder(), badRender=new LongAdder(), badOwner=new LongAdder(), badParent=new LongAdder(), badIdentity=new LongAdder(), badFaceData=new LongAdder(), badEmpty=new LongAdder(), badMalformed=new LongAdder();
    }
}
