package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental direct baker for the strict vanilla generated-item path.
 *
 * <p>The stock 1.21.1 path first expands each sprite into a large graph of {@code BlockElement}, face-map,
 * {@code BlockElementFace}, {@code BlockFaceUV}, float-array and {@code Vector3f} objects and only then feeds the
 * values into {@link FaceBakery}. This implementation keeps the stock {@link FaceBakery} authoritative but computes
 * the same generated-item topology into primitive arrays and feeds the minimum parameters directly to it.</p>
 *
 * <p>Non-geometry model metadata is deliberately not reimplemented. A generated BlockModel with an empty element
 * list is baked through stock {@code bakeVanilla}; the returned model is wrapped only to replace its quad lists.</p>
 */
public final class DirectGeneratedItemBaker {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/GeneratedItemDirect");
    private static final FaceBakery FACE_BAKERY = new FaceBakery();
    private static final ItemModelGenerator STOCK_GENERATOR = new ItemModelGenerator();
    private static final String[] LAYERS = {"layer0", "layer1", "layer2", "layer3", "layer4"};
    private static final Direction[] FRONT_BACK_ORDER = computeFrontBackOrder();

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.generatedItemDirectBake", "true"));
    private static final boolean VERIFY = Boolean.parseBoolean(
            System.getProperty("boot_optim.generatedItemDirectBake.verify", "true"));

    private static final LongAdder ELIGIBLE_CALLS = new LongAdder();
    private static final LongAdder CANDIDATE_NS = new LongAdder();
    private static final LongAdder TOPOLOGY_NS = new LongAdder();
    private static final LongAdder METADATA_NS = new LongAdder();
    private static final LongAdder QUAD_BAKE_NS = new LongAdder();
    private static final LongAdder STOCK_VERIFY_NS = new LongAdder();
    private static final LongAdder LAYERS_BAKED = new LongAdder();
    private static final LongAdder SIDE_SPANS = new LongAdder();
    private static final LongAdder QUADS_BAKED = new LongAdder();
    private static final LongAdder STOCK_EQUIVALENT_ELEMENTS = new LongAdder();
    private static final LongAdder STOCK_EQUIVALENT_FACES = new LongAdder();
    private static final LongAdder VERIFY_MATCHES = new LongAdder();
    private static final LongAdder VERIFY_MISMATCHES = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final AtomicInteger LOGGED_MISMATCHES = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> LOGGER.info(
                "BOOTOPTIM_GENERATED_ITEM_DIRECT summary=shutdown enabled={} verify={} eligible_calls={} candidate_ms={} topology_ms={} metadata_ms={} quad_bake_ms={} stock_verify_ms={} layers={} side_spans={} quads={} stock_equivalent_elements={} stock_equivalent_faces={} verify_matches={} verify_mismatches={} fallbacks={}",
                ENABLED,
                VERIFY,
                ELIGIBLE_CALLS.sum(),
                millis(CANDIDATE_NS),
                millis(TOPOLOGY_NS),
                millis(METADATA_NS),
                millis(QUAD_BAKE_NS),
                millis(STOCK_VERIFY_NS),
                LAYERS_BAKED.sum(),
                SIDE_SPANS.sum(),
                QUADS_BAKED.sum(),
                STOCK_EQUIVALENT_ELEMENTS.sum(),
                STOCK_EQUIVALENT_FACES.sum(),
                VERIFY_MATCHES.sum(),
                VERIFY_MISMATCHES.sum(),
                FALLBACKS.sum()), "BootOptim-generated-item-direct-report"));
    }

    private DirectGeneratedItemBaker() {
    }

    /**
     * @return a model to return immediately, or {@code null} to fail open to the untouched stock path.
     */
    @Nullable
    public static BakedModel tryBake(
            BlockModel blockModel,
            ModelBaker modelBaker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d) {
        if (!ENABLED) {
            return null;
        }

        ELIGIBLE_CALLS.increment();

        if (VERIFY) {
            // In verification mode the stock result is produced first and is always returned, so candidate work can
            // never alter what Minecraft observes even when a comparison fails.
            long stockStart = System.nanoTime();
            BakedModel stock = STOCK_GENERATOR.generateBlockModel(spriteGetter, blockModel)
                    .bakeVanilla(modelBaker, blockModel, spriteGetter, modelState, guiLight3d);
            STOCK_VERIFY_NS.add(System.nanoTime() - stockStart);

            try {
                BakedModel candidate = bakeCandidate(blockModel, modelBaker, spriteGetter, modelState, guiLight3d);
                String mismatch = compare(stock, candidate);
                if (mismatch == null) {
                    VERIFY_MATCHES.increment();
                } else {
                    VERIFY_MISMATCHES.increment();
                    logMismatch(blockModel, mismatch);
                }
            } catch (RuntimeException unexpected) {
                VERIFY_MISMATCHES.increment();
                FALLBACKS.increment();
                logMismatch(blockModel, "candidate exception: " + unexpected);
            }
            return stock;
        }

        try {
            return bakeCandidate(blockModel, modelBaker, spriteGetter, modelState, guiLight3d);
        } catch (RuntimeException unexpected) {
            FALLBACKS.increment();
            if (LOGGED_MISMATCHES.getAndIncrement() < 8) {
                LOGGER.warn("Direct generated-item bake failed open for {}", blockModel.name, unexpected);
            }
            return null;
        }
    }

    private static BakedModel bakeCandidate(
            BlockModel blockModel,
            ModelBaker modelBaker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d) {
        long candidateStart = System.nanoTime();

        Map<String, Either<Material, String>> textures = new HashMap<>();
        LayerData[] layerData = new LayerData[LAYERS.length];
        int layerCount = 0;
        int quadCount = 0;
        long localSideSpans = 0L;
        long localElements = 0L;
        long localFaces = 0L;

        long topologyStart = System.nanoTime();
        for (int layerIndex = 0; layerIndex < LAYERS.length; layerIndex++) {
            String layer = LAYERS[layerIndex];
            if (!blockModel.hasTexture(layer)) {
                break;
            }

            Material material = blockModel.getMaterial(layer);
            textures.put(layer, Either.left(material));
            TextureAtlasSprite sprite = spriteGetter.apply(material);
            Topology topology = buildTopology(sprite.contents());
            layerData[layerCount++] = new LayerData(layerIndex, layer, sprite, topology);

            int sideCount = topology.orderSize;
            quadCount += 2 + sideCount;
            localSideSpans += sideCount;
            localElements += 1L + sideCount;
            localFaces += 2L + sideCount;
        }
        TOPOLOGY_NS.add(System.nanoTime() - topologyStart);

        // Match ItemModelGenerator.generateBlockModel's texture/metadata construction exactly, but omit geometry.
        textures.put("particle", blockModel.hasTexture("particle")
                ? Either.left(blockModel.getMaterial("particle"))
                : textures.get("layer0"));

        BlockModel generatedMetadata = new BlockModel(
                null,
                List.of(),
                textures,
                false,
                blockModel.getGuiLight(),
                blockModel.getTransforms(),
                blockModel.getOverrides());
        generatedMetadata.name = blockModel.name;
        generatedMetadata.customData.copyFrom(blockModel.customData);
        generatedMetadata.customData.setGui3d(false);

        long metadataStart = System.nanoTime();
        BakedModel metadataModel = generatedMetadata.bakeVanilla(
                modelBaker,
                blockModel,
                spriteGetter,
                modelState,
                guiLight3d);
        METADATA_NS.add(System.nanoTime() - metadataStart);

        long quadStart = System.nanoTime();
        ArrayList<BakedQuad> quads = new ArrayList<>(quadCount);
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (int i = 0; i < layerCount; i++) {
            bakeLayer(layerData[i], modelState, quads, from, to);
        }
        QUAD_BAKE_NS.add(System.nanoTime() - quadStart);

        LAYERS_BAKED.add(layerCount);
        SIDE_SPANS.add(localSideSpans);
        QUADS_BAKED.add(quads.size());
        STOCK_EQUIVALENT_ELEMENTS.add(localElements);
        STOCK_EQUIVALENT_FACES.add(localFaces);
        CANDIDATE_NS.add(System.nanoTime() - candidateStart);

        return new DirectModel(metadataModel, quads);
    }

    private static Topology buildTopology(SpriteContents sprite) {
        int width = sprite.width();
        int height = sprite.height();
        Topology topology = new Topology(width, height);

        // Stock ItemModelGenerator unions edge transitions across every unique animation frame. Keep the same frame,
        // y, x and UP/DOWN/LEFT/RIGHT traversal order so first-appearance ordering of spans remains identical.
        sprite.getUniqueFrames().forEach(frame -> {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (sprite.isTransparent(frame, x, y)) {
                        continue;
                    }

                    if (y == 0 || sprite.isTransparent(frame, x, y - 1)) {
                        topology.edge(0, y, x); // UP: anchor=y, extent=x
                    }
                    if (y == height - 1 || sprite.isTransparent(frame, x, y + 1)) {
                        topology.edge(1, y, x); // DOWN: anchor=y, extent=x
                    }
                    if (x == 0 || sprite.isTransparent(frame, x - 1, y)) {
                        topology.edge(2, x, y); // LEFT/EAST: anchor=x, extent=y
                    }
                    if (x == width - 1 || sprite.isTransparent(frame, x + 1, y)) {
                        topology.edge(3, x, y); // RIGHT/WEST: anchor=x, extent=y
                    }
                }
            }
        });
        return topology;
    }

    private static void bakeLayer(
            LayerData layer,
            ModelState modelState,
            List<BakedQuad> quads,
            Vector3f from,
            Vector3f to) {
        TextureAtlasSprite sprite = layer.sprite;
        SpriteContents contents = sprite.contents();
        float width = (float) contents.width();
        float height = (float) contents.height();
        float scaleX = 16.0F / width;
        float scaleY = 16.0F / height;

        // One mutable face/UV pair is sufficient for every quad in this layer. FaceBakery copies all baked vertex
        // data and restores the temporary UV shrink it performs internally before returning.
        float[] uvs = new float[4];
        BlockFaceUV faceUv = new BlockFaceUV(uvs, 0);
        BlockElementFace face = new BlockElementFace(null, layer.layerIndex, layer.name, faceUv);

        from.set(0.0F, 0.0F, 7.5F);
        to.set(16.0F, 16.0F, 8.5F);
        for (Direction direction : FRONT_BACK_ORDER) {
            if (direction == Direction.SOUTH) {
                setUv(uvs, 0.0F, 0.0F, 16.0F, 16.0F);
            } else {
                setUv(uvs, 16.0F, 0.0F, 0.0F, 16.0F);
            }
            quads.add(FACE_BAKERY.bakeQuad(from, to, face, sprite, direction, modelState, null, true));
        }

        float seamExpand = -sprite.uvShrinkRatio();
        Topology topology = layer.topology;
        for (int orderIndex = 0; orderIndex < topology.orderSize; orderIndex++) {
            int key = topology.order[orderIndex];
            int facing;
            int anchor;
            if (key < topology.height) {
                facing = 0;
                anchor = key;
            } else if (key < topology.height * 2) {
                facing = 1;
                anchor = key - topology.height;
            } else if (key < topology.height * 2 + topology.width) {
                facing = 2;
                anchor = key - topology.height * 2;
            } else {
                facing = 3;
                anchor = key - topology.height * 2 - topology.width;
            }

            int min = topology.minPlusOne[key] - 1;
            int max = topology.max[key];

            float x1 = 0.0F;
            float y1 = 0.0F;
            float x2 = 0.0F;
            float y2 = 0.0F;
            float u0 = 0.0F;
            float v0 = 0.0F;
            float u1 = 0.0F;
            float v1 = 0.0F;
            Direction direction;

            switch (facing) {
                case 0 -> { // UP
                    u0 = min;
                    x1 = min;
                    x2 = u1 = max + 1.0F;
                    v0 = anchor;
                    y1 = anchor;
                    y2 = anchor;
                    v1 = anchor + 1.0F;
                    direction = Direction.UP;
                }
                case 1 -> { // DOWN
                    v0 = anchor;
                    v1 = anchor + 1.0F;
                    u0 = min;
                    x1 = min;
                    x2 = u1 = max + 1.0F;
                    y1 = anchor + 1.0F;
                    y2 = anchor + 1.0F;
                    direction = Direction.DOWN;
                }
                case 2 -> { // LEFT in ItemModelGenerator, faces EAST
                    u0 = anchor;
                    x1 = anchor;
                    x2 = anchor;
                    u1 = anchor + 1.0F;
                    v1 = min;
                    y1 = min;
                    y2 = v0 = max + 1.0F;
                    direction = Direction.EAST;
                }
                case 3 -> { // RIGHT in ItemModelGenerator, faces WEST
                    u0 = anchor;
                    u1 = anchor + 1.0F;
                    x1 = anchor + 1.0F;
                    x2 = anchor + 1.0F;
                    v1 = min;
                    y1 = min;
                    y2 = v0 = max + 1.0F;
                    direction = Direction.WEST;
                }
                default -> throw new IllegalStateException("Unexpected generated-item facing " + facing);
            }

            // Preserve ItemModelGenerator's exact float operation order before applying NeoForge's seam fix.
            x1 *= scaleX;
            x2 *= scaleX;
            y1 *= scaleY;
            y2 *= scaleY;
            y1 = 16.0F - y1;
            y2 = 16.0F - y2;
            u0 *= scaleX;
            u1 *= scaleX;
            v0 *= scaleY;
            v1 *= scaleY;

            // ClientHooks.fixItemModelSeams, inlined exactly for the one-face X/Y edge elements.
            x1 = Mth.clamp(Mth.lerp(seamExpand, x1, 8.0F), 0.0F, 16.0F);
            y1 = Mth.clamp(Mth.lerp(seamExpand, y1, 8.0F), 0.0F, 16.0F);
            x2 = Mth.clamp(Mth.lerp(seamExpand, x2, 8.0F), 0.0F, 16.0F);
            y2 = Mth.clamp(Mth.lerp(seamExpand, y2, 8.0F), 0.0F, 16.0F);

            if (direction.getAxis() == Direction.Axis.Y) {
                float centerU = (u0 + u0 + u1 + u1) / 4.0F;
                u0 = Mth.clamp(Mth.lerp(seamExpand, u0, centerU), 0.0F, 16.0F);
                u1 = Mth.clamp(Mth.lerp(seamExpand, u1, centerU), 0.0F, 16.0F);
            } else {
                float centerV = (v0 + v0 + v1 + v1) / 4.0F;
                v0 = Mth.clamp(Mth.lerp(seamExpand, v0, centerV), 0.0F, 16.0F);
                v1 = Mth.clamp(Mth.lerp(seamExpand, v1, centerV), 0.0F, 16.0F);
            }

            from.set(x1, y1, 7.5F);
            to.set(x2, y2, 8.5F);
            setUv(uvs, u0, v0, u1, v1);
            quads.add(FACE_BAKERY.bakeQuad(from, to, face, sprite, direction, modelState, null, true));
        }
    }

    private static void setUv(float[] uvs, float u0, float v0, float u1, float v1) {
        uvs[0] = u0;
        uvs[1] = v0;
        uvs[2] = u1;
        uvs[3] = v1;
    }

    @Nullable
    private static String compare(BakedModel stock, BakedModel candidate) {
        if (stock.useAmbientOcclusion() != candidate.useAmbientOcclusion()) {
            return "model ambient-occlusion flag";
        }
        if (stock.usesBlockLight() != candidate.usesBlockLight()) {
            return "model block-light flag";
        }
        if (stock.isGui3d() != candidate.isGui3d()) {
            return "model gui3d flag";
        }
        if (stock.isCustomRenderer() != candidate.isCustomRenderer()) {
            return "model custom-renderer flag";
        }
        if (stock.getParticleIcon() != candidate.getParticleIcon()) {
            return "particle sprite identity";
        }
        if (stock.getOverrides().getOverrides().size() != candidate.getOverrides().getOverrides().size()) {
            return "override count";
        }

        for (ItemDisplayContext context : ItemDisplayContext.values()) {
            if (!Objects.equals(
                    stock.getTransforms().getTransform(context),
                    candidate.getTransforms().getTransform(context))) {
                return "transform " + context;
            }
        }

        String mismatch = compareQuadList(
                stock.getQuads(null, null, RandomSource.create(0L)),
                candidate.getQuads(null, null, RandomSource.create(0L)),
                "unculled");
        if (mismatch != null) {
            return mismatch;
        }

        for (Direction side : Direction.values()) {
            mismatch = compareQuadList(
                    stock.getQuads(null, side, RandomSource.create(0L)),
                    candidate.getQuads(null, side, RandomSource.create(0L)),
                    "culled/" + side);
            if (mismatch != null) {
                return mismatch;
            }
        }

        // Also exercise NeoForge's extended quad path; a wrapper that accidentally delegated this call to the empty
        // metadata model would pass the vanilla getter comparison but render invisibly through modded item paths.
        mismatch = compareQuadList(
                stock.getQuads(null, null, RandomSource.create(0L), ModelData.EMPTY, null),
                candidate.getQuads(null, null, RandomSource.create(0L), ModelData.EMPTY, null),
                "extended/unculled");
        if (mismatch != null) {
            return mismatch;
        }

        if (stock.getRenderPasses(ItemStack.EMPTY, false).size()
                != candidate.getRenderPasses(ItemStack.EMPTY, false).size()) {
            return "render-pass count";
        }
        return null;
    }

    @Nullable
    private static String compareQuadList(List<BakedQuad> stock, List<BakedQuad> candidate, String label) {
        if (stock.size() != candidate.size()) {
            return label + " quad count stock=" + stock.size() + " candidate=" + candidate.size();
        }
        for (int i = 0; i < stock.size(); i++) {
            BakedQuad expected = stock.get(i);
            BakedQuad actual = candidate.get(i);
            if (!Arrays.equals(expected.getVertices(), actual.getVertices())) {
                return label + " vertices at " + i;
            }
            if (expected.getTintIndex() != actual.getTintIndex()) {
                return label + " tint at " + i;
            }
            if (expected.getDirection() != actual.getDirection()) {
                return label + " direction at " + i;
            }
            if (expected.isShade() != actual.isShade()) {
                return label + " shade at " + i;
            }
            if (expected.hasAmbientOcclusion() != actual.hasAmbientOcclusion()) {
                return label + " quad ambient-occlusion at " + i;
            }
            if (expected.getSprite() != actual.getSprite()) {
                return label + " sprite identity at " + i;
            }
        }
        return null;
    }

    private static void logMismatch(BlockModel model, String reason) {
        if (LOGGED_MISMATCHES.getAndIncrement() < 16) {
            LOGGER.warn("Generated-item direct verifier mismatch model={} reason={}", model.name, reason);
        }
    }

    private static Direction[] computeFrontBackOrder() {
        // ItemModelGenerator creates the two front/back faces in a plain HashMap. Recreate a single map once so our
        // order follows the JVM's actual Direction-enum hash bucket order instead of assuming insertion order.
        Map<Direction, Boolean> orderProbe = new HashMap<>();
        orderProbe.put(Direction.SOUTH, Boolean.TRUE);
        orderProbe.put(Direction.NORTH, Boolean.TRUE);
        return orderProbe.keySet().toArray(Direction[]::new);
    }

    private static String millis(LongAdder nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos.sum() / 1_000_000.0);
    }

    private record LayerData(int layerIndex, String name, TextureAtlasSprite sprite, Topology topology) {
    }

    /** Primitive equivalent of ItemModelGenerator's ordered List<Span>. */
    private static final class Topology {
        private final int width;
        private final int height;
        private final int[] minPlusOne;
        private final int[] max;
        private final int[] order;
        private int orderSize;

        private Topology(int width, int height) {
            this.width = width;
            this.height = height;
            int keyCount = 2 * height + 2 * width;
            this.minPlusOne = new int[keyCount];
            this.max = new int[keyCount];
            this.order = new int[keyCount];
        }

        private void edge(int facing, int anchor, int extent) {
            int key = switch (facing) {
                case 0 -> anchor;
                case 1 -> height + anchor;
                case 2 -> 2 * height + anchor;
                case 3 -> 2 * height + width + anchor;
                default -> throw new IllegalStateException("Unexpected generated-item facing " + facing);
            };

            int encodedMin = minPlusOne[key];
            if (encodedMin == 0) {
                minPlusOne[key] = extent + 1;
                max[key] = extent;
                order[orderSize++] = key;
                return;
            }

            int min = encodedMin - 1;
            if (extent < min) {
                minPlusOne[key] = extent + 1;
            }
            if (extent > max[key]) {
                max[key] = extent;
            }
        }
    }

    private static final class DirectModel extends BakedModelWrapper<BakedModel> {
        private static final List<BakedQuad> EMPTY_QUADS = List.of();
        private final List<BakedQuad> quads;

        private DirectModel(BakedModel metadataModel, List<BakedQuad> quads) {
            super(metadataModel);
            this.quads = quads;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return side == null ? quads : EMPTY_QUADS;
        }

        @Override
        public List<BakedQuad> getQuads(
                @Nullable BlockState state,
                @Nullable Direction side,
                RandomSource rand,
                ModelData data,
                @Nullable RenderType renderType) {
            return side == null ? quads : EMPTY_QUADS;
        }

        @Override
        public BakedModel applyTransform(
                ItemDisplayContext cameraTransformType,
                PoseStack poseStack,
                boolean applyLeftHandTransform) {
            getTransforms().getTransform(cameraTransformType).apply(applyLeftHandTransform, poseStack);
            return this;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
            return List.of(this);
        }
    }
}
