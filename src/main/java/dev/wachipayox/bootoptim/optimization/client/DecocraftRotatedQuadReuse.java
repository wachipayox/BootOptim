package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reuses Decocraft 3.0.11 Blockbench geometry across pure horizontal quarter-turn variants.
 *
 * <p>The first matching geometry/context/override combination is baked by Decocraft normally.
 * Later calls are derived only when their ModelState differs by an exact Y-axis quarter turn and
 * all safety checks pass. Any unexpected model shape, NeoForge extension, UV-lock state, context,
 * override object, vertex format or transformation falls back to Decocraft's original bake.</p>
 */
public final class DecocraftRotatedQuadReuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftBakeReuse");
    private static final String GEOMETRY_CLASS = "com.razz.decocraft.models.bbmodel.BBModelGeometryLoader$BBGeometry";
    private static final String BAKED_MODEL_CLASS = "com.razz.decocraft.models.bbmodel.BlockbenchModel";
    private static final float EPSILON = 1.0e-4F;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.decocraftQuarterTurnReuse", "true"));

    /** Cache lifetime is exactly one ModelBakery#bakeModels call. */
    private static final IdentityHashMap<IUnbakedGeometry<?>, List<CachedBase>> BASES = new IdentityHashMap<>();
    private static final EnumMap<QuarterTurn, Integer> DERIVED_BY_ROTATION = new EnumMap<>(QuarterTurn.class);

    private static int decocraftCalls;
    private static int baseBakes;
    private static int exactReuses;
    private static int derivedBakes;
    private static int fallbacks;
    private static int rejectedModels;
    private static long derivedQuads;
    private static long derivedNanos;
    private static long fallbackNanos;

    private DecocraftRotatedQuadReuse() {}

    public static synchronized void beginModelBake() {
        BASES.clear();
        DERIVED_BY_ROTATION.clear();
        decocraftCalls = 0;
        baseBakes = 0;
        exactReuses = 0;
        derivedBakes = 0;
        fallbacks = 0;
        rejectedModels = 0;
        derivedQuads = 0L;
        derivedNanos = 0L;
        fallbackNanos = 0L;
    }

    public static BakedModel bake(
            IUnbakedGeometry<?> geometry,
            IGeometryBakingContext context,
            ModelState modelState,
            ItemOverrides overrides,
            Supplier<BakedModel> originalBake) {
        if (!ENABLED
                || modelState == null
                || context == null
                || overrides == null
                || !GEOMETRY_CLASS.equals(geometry.getClass().getName())) {
            return originalBake.get();
        }

        synchronized (DecocraftRotatedQuadReuse.class) {
            decocraftCalls++;
        }

        CachedBase cached;
        synchronized (BASES) {
            List<CachedBase> candidates = BASES.get(geometry);
            cached = findCompatible(candidates, context, overrides, modelState.isUvLocked());
        }

        if (cached == null) {
            BakedModel baked = originalBake.get();
            if (baked == null) {
                return null;
            }
            CachedBase candidate = captureBase(baked, context, overrides, modelState);
            synchronized (BASES) {
                BASES.computeIfAbsent(geometry, ignored -> new ArrayList<>()).add(candidate);
            }
            synchronized (DecocraftRotatedQuadReuse.class) {
                baseBakes++;
                if (!candidate.safe) {
                    rejectedModels++;
                }
            }
            return baked;
        }

        if (!cached.safe) {
            return fallback(originalBake);
        }

        Matrix4f target = new Matrix4f(modelState.getRotation().getMatrix());
        Matrix4f relative = new Matrix4f(target).mul(new Matrix4f(cached.rotation).invert());
        QuarterTurn turn = QuarterTurn.classify(relative);
        if (turn == null) {
            return fallback(originalBake);
        }
        if (turn == QuarterTurn.IDENTITY) {
            synchronized (DecocraftRotatedQuadReuse.class) {
                exactReuses++;
            }
            return cached.model;
        }

        long started = System.nanoTime();
        List<BakedQuad> rotated = rotateQuads(cached.quads, relative);
        if (rotated == null) {
            return fallback(originalBake);
        }
        BakedModel derived = new DerivedBakedModel(cached.model, rotated);
        long elapsed = System.nanoTime() - started;
        synchronized (DecocraftRotatedQuadReuse.class) {
            derivedBakes++;
            derivedQuads += rotated.size();
            derivedNanos += elapsed;
            DERIVED_BY_ROTATION.merge(turn, 1, Integer::sum);
        }
        return derived;
    }

    public static synchronized void finishModelBake() {
        try {
            if (decocraftCalls != 0) {
                LOGGER.info(
                        "BOOTOPTIM_DECOCRAFT_QUAD_REUSE status={} calls={} base_bakes={} derived_bakes={} exact_reuses={} fallbacks={} rejected_models={} derived_quads={} derived_ms={} fallback_ms={} rotation_90={} rotation_180={} rotation_270={}",
                        ENABLED ? "enabled" : "disabled",
                        decocraftCalls,
                        baseBakes,
                        derivedBakes,
                        exactReuses,
                        fallbacks,
                        rejectedModels,
                        derivedQuads,
                        ms(derivedNanos),
                        ms(fallbackNanos),
                        DERIVED_BY_ROTATION.getOrDefault(QuarterTurn.DEG_90, 0),
                        DERIVED_BY_ROTATION.getOrDefault(QuarterTurn.DEG_180, 0),
                        DERIVED_BY_ROTATION.getOrDefault(QuarterTurn.DEG_270, 0));
            }
        } finally {
            BASES.clear();
            DERIVED_BY_ROTATION.clear();
        }
    }

    private static @Nullable CachedBase findCompatible(
            @Nullable List<CachedBase> candidates,
            IGeometryBakingContext context,
            ItemOverrides overrides,
            boolean uvLocked) {
        if (candidates == null) {
            return null;
        }
        for (CachedBase candidate : candidates) {
            if (candidate.context == context
                    && candidate.overrides == overrides
                    && candidate.uvLocked == uvLocked) {
                return candidate;
            }
        }
        return null;
    }

    private static CachedBase captureBase(
            BakedModel model,
            IGeometryBakingContext context,
            ItemOverrides overrides,
            ModelState state) {
        if (!BAKED_MODEL_CLASS.equals(model.getClass().getName()) || !usesDefaultExtendedQuadPath(model.getClass())) {
            return CachedBase.rejected(model, context, overrides, state);
        }

        RandomSource random = RandomSource.create(0L);
        List<BakedQuad> quads = List.copyOf(model.getQuads(null, null, random));
        int expectedSize = IQuadTransformer.STRIDE * 4;
        for (BakedQuad quad : quads) {
            if (quad.getVertices().length != expectedSize) {
                return CachedBase.rejected(model, context, overrides, state);
            }
        }
        for (Direction side : Direction.values()) {
            random.setSeed(0L);
            if (!model.getQuads(null, side, random).isEmpty()) {
                return CachedBase.rejected(model, context, overrides, state);
            }
        }
        return new CachedBase(
                model,
                context,
                overrides,
                state.isUvLocked(),
                new Matrix4f(state.getRotation().getMatrix()),
                quads,
                true);
    }

    /**
     * The derived wrapper replaces the quad list. If Decocraft starts overriding NeoForge's
     * ModelData/render-type-aware quad method in a future build, the safe behavior is to stop
     * deriving and let Decocraft bake every orientation itself.
     */
    private static boolean usesDefaultExtendedQuadPath(Class<?> modelClass) {
        try {
            Method method = modelClass.getMethod(
                    "getQuads",
                    BlockState.class,
                    Direction.class,
                    RandomSource.class,
                    ModelData.class,
                    RenderType.class);
            return method.getDeclaringClass() == IBakedModelExtension.class;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static BakedModel fallback(Supplier<BakedModel> originalBake) {
        long started = System.nanoTime();
        BakedModel result = originalBake.get();
        long elapsed = System.nanoTime() - started;
        synchronized (DecocraftRotatedQuadReuse.class) {
            fallbacks++;
            fallbackNanos += elapsed;
        }
        return result;
    }

    private static @Nullable List<BakedQuad> rotateQuads(List<BakedQuad> source, Matrix4f relative) {
        ArrayList<BakedQuad> result = new ArrayList<>(source.size());
        for (BakedQuad quad : source) {
            int[] sourceData = quad.getVertices();
            if (sourceData.length != IQuadTransformer.STRIDE * 4
                    || IQuadTransformer.POSITION < 0
                    || IQuadTransformer.NORMAL < 0) {
                return null;
            }
            int[] data = sourceData.clone();
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
                float x = Float.intBitsToFloat(sourceData[base]) - 0.5F;
                float y = Float.intBitsToFloat(sourceData[base + 1]) - 0.5F;
                float z = Float.intBitsToFloat(sourceData[base + 2]) - 0.5F;

                float rotatedX = relative.m00() * x + relative.m10() * y + relative.m20() * z + relative.m30();
                float rotatedY = relative.m01() * x + relative.m11() * y + relative.m21() * z + relative.m31();
                float rotatedZ = relative.m02() * x + relative.m12() * y + relative.m22() * z + relative.m32();

                data[base] = Float.floatToRawIntBits(rotatedX + 0.5F);
                data[base + 1] = Float.floatToRawIntBits(rotatedY + 0.5F);
                data[base + 2] = Float.floatToRawIntBits(rotatedZ + 0.5F);
            }

            Direction direction = rotateDirection(quad.getDirection(), relative);
            for (int vertex = 0; vertex < 4; vertex++) {
                int normalOffset = vertex * IQuadTransformer.STRIDE + IQuadTransformer.NORMAL;
                int originalNormal = sourceData[normalOffset];
                data[normalOffset] = packNormal(direction, originalNormal & 0xFF000000);
            }
            result.add(new BakedQuad(
                    data,
                    quad.getTintIndex(),
                    direction,
                    quad.getSprite(),
                    quad.isShade(),
                    quad.hasAmbientOcclusion()));
        }
        return List.copyOf(result);
    }

    private static Direction rotateDirection(Direction direction, Matrix4f matrix) {
        float x = direction.getStepX();
        float y = direction.getStepY();
        float z = direction.getStepZ();
        float rotatedX = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z;
        float rotatedY = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z;
        float rotatedZ = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z;

        float absX = Math.abs(rotatedX);
        float absY = Math.abs(rotatedY);
        float absZ = Math.abs(rotatedZ);
        if (absY >= absX && absY >= absZ) {
            return rotatedY >= 0.0F ? Direction.UP : Direction.DOWN;
        }
        if (absX >= absZ) {
            return rotatedX >= 0.0F ? Direction.EAST : Direction.WEST;
        }
        return rotatedZ >= 0.0F ? Direction.SOUTH : Direction.NORTH;
    }

    private static int packNormal(Direction direction, int padding) {
        int x = ((byte) (direction.getStepX() * 127.0F)) & 0xFF;
        int y = ((byte) (direction.getStepY() * 127.0F)) & 0xFF;
        int z = ((byte) (direction.getStepZ() * 127.0F)) & 0xFF;
        return padding | x | (y << 8) | (z << 16);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record CachedBase(
            BakedModel model,
            IGeometryBakingContext context,
            ItemOverrides overrides,
            boolean uvLocked,
            Matrix4f rotation,
            List<BakedQuad> quads,
            boolean safe) {
        static CachedBase rejected(
                BakedModel model,
                IGeometryBakingContext context,
                ItemOverrides overrides,
                ModelState state) {
            return new CachedBase(
                    model,
                    context,
                    overrides,
                    state.isUvLocked(),
                    new Matrix4f(state.getRotation().getMatrix()),
                    List.of(),
                    false);
        }
    }

    private enum QuarterTurn {
        IDENTITY(1, 0, 0, 1),
        DEG_90(0, 1, -1, 0),
        DEG_180(-1, 0, 0, -1),
        DEG_270(0, -1, 1, 0);

        private final int m00;
        private final int m20;
        private final int m02;
        private final int m22;

        QuarterTurn(int m00, int m20, int m02, int m22) {
            this.m00 = m00;
            this.m20 = m20;
            this.m02 = m02;
            this.m22 = m22;
        }

        static @Nullable QuarterTurn classify(Matrix4f matrix) {
            if (!near(matrix.m01(), 0.0F)
                    || !near(matrix.m10(), 0.0F)
                    || !near(matrix.m12(), 0.0F)
                    || !near(matrix.m21(), 0.0F)
                    || !near(matrix.m11(), 1.0F)
                    || !near(matrix.m03(), 0.0F)
                    || !near(matrix.m13(), 0.0F)
                    || !near(matrix.m23(), 0.0F)
                    || !near(matrix.m30(), 0.0F)
                    || !near(matrix.m31(), 0.0F)
                    || !near(matrix.m32(), 0.0F)
                    || !near(matrix.m33(), 1.0F)) {
                return null;
            }
            for (QuarterTurn turn : values()) {
                if (near(matrix.m00(), turn.m00)
                        && near(matrix.m20(), turn.m20)
                        && near(matrix.m02(), turn.m02)
                        && near(matrix.m22(), turn.m22)) {
                    return turn;
                }
            }
            return null;
        }

        private static boolean near(float actual, float expected) {
            return Math.abs(actual - expected) <= EPSILON;
        }
    }

    private static final class DerivedBakedModel extends BakedModelWrapper<BakedModel> {
        private final List<BakedQuad> quads;

        private DerivedBakedModel(BakedModel originalModel, List<BakedQuad> quads) {
            super(originalModel);
            this.quads = quads;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
            return side == null ? quads : List.of();
        }

        @Override
        public List<BakedQuad> getQuads(
                @Nullable BlockState state,
                @Nullable Direction side,
                RandomSource random,
                ModelData data,
                @Nullable RenderType renderType) {
            return side == null ? quads : List.of();
        }

        @Override
        public BakedModel applyTransform(
                ItemDisplayContext transformType,
                PoseStack poseStack,
                boolean applyLeftHandTransform) {
            BakedModel transformed = originalModel.applyTransform(transformType, poseStack, applyLeftHandTransform);
            return transformed == originalModel ? this : transformed;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
            List<BakedModel> passes = originalModel.getRenderPasses(itemStack, fabulous);
            if (passes.size() == 1 && passes.getFirst() == originalModel) {
                return List.of(this);
            }
            return passes;
        }
    }
}
