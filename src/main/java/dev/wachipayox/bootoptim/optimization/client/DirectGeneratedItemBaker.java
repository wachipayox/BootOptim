package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.FaceBakery;
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

/**
 * Direct baker for the strict vanilla generated-item path.
 *
 * <p>Vanilla 1.21.1 expands each sprite into a large temporary BlockElement graph before feeding
 * those values into FaceBakery. This implementation keeps stock/NeoForge FaceBakery authoritative
 * while computing the same ordered edge topology in primitive arrays and baking final quads directly.</p>
 *
 * <p>Non-geometry metadata is still produced by a stock empty generated BlockModel. The returned
 * model is wrapped only to replace its quad lists. Any unexpected runtime failure returns null so
 * the caller can fail open to the untouched stock generated-item shortcut.</p>
 */
public final class DirectGeneratedItemBaker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FaceBakery FACE_BAKERY = new FaceBakery();
    private static final String[] LAYERS = {"layer0", "layer1", "layer2", "layer3", "layer4"};
    private static final Direction[] FRONT_BACK_ORDER = computeFrontBackOrder();
    private static final String TRIMMABLE_TOOLS_NAMESPACE = "trimmable_tools";
    private static final float TRIMMABLE_TOOLS_SIDE_EXPANSION = 0.01F;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.generatedItemDirectBake", "true"));
    private static final AtomicInteger LOGGED_FALLBACKS = new AtomicInteger();

    private DirectGeneratedItemBaker() {
    }

    /**
     * @return the direct model, or {@code null} to leave the stock path untouched.
     */
    @Nullable
    public static BakedModel tryBake(
            BlockModel blockModel,
            ModelBaker modelBaker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d) {
        if (!ENABLED || !blockModel.hasTexture("layer0")) {
            return null;
        }

        try {
            return bakeCandidate(blockModel, modelBaker, spriteGetter, modelState, guiLight3d);
        } catch (RuntimeException unexpected) {
            if (LOGGED_FALLBACKS.getAndIncrement() < 8) {
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
        Map<String, Either<Material, String>> textures = new HashMap<>();
        LayerData[] layerData = new LayerData[LAYERS.length];
        int layerCount = 0;
        int quadCount = 0;

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
            quadCount += 2 + topology.orderSize;
        }

        if (layerCount == 0) {
            return null;
        }

        // Match ItemModelGenerator.generateBlockModel's metadata construction while omitting geometry.
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

        // Use the exact BlockModel#bake route used by ModelBakery's generated-item shortcut.
        BakedModel metadataModel = generatedMetadata.bake(
                modelBaker,
                blockModel,
                spriteGetter,
                modelState,
                guiLight3d);

        ArrayList<BakedQuad> quads = new ArrayList<>(quadCount);
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (int i = 0; i < layerCount; i++) {
            bakeLayer(layerData[i], modelState, quads, from, to);
        }

        return new DirectModel(metadataModel, quads);
    }

    private static Topology buildTopology(SpriteContents sprite) {
        int width = sprite.width();
        int height = sprite.height();
        Topology topology = new Topology(width, height);

        // Stock ItemModelGenerator unions transitions across every unique animation frame. Preserve
        // frame -> y -> x -> UP/DOWN/LEFT/RIGHT traversal so first-appearance ordering is identical.
        sprite.getUniqueFrames().forEach(frame -> {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (sprite.isTransparent(frame, x, y)) {
                        continue;
                    }

                    if (y == 0 || sprite.isTransparent(frame, x, y - 1)) {
                        topology.edge(0, y, x);
                    }
                    if (y == height - 1 || sprite.isTransparent(frame, x, y + 1)) {
                        topology.edge(1, y, x);
                    }
                    if (x == 0 || sprite.isTransparent(frame, x - 1, y)) {
                        topology.edge(2, x, y);
                    }
                    if (x == width - 1 || sprite.isTransparent(frame, x + 1, y)) {
                        topology.edge(3, x, y);
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

        // FaceBakery copies baked vertex data and restores its temporary UV shrink before returning,
        // so one mutable face/UV pair can be reused for every quad in this layer.
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
                case 0 -> {
                    u0 = min;
                    x1 = min;
                    x2 = u1 = max + 1.0F;
                    v0 = anchor;
                    y1 = anchor;
                    y2 = anchor;
                    v1 = anchor + 1.0F;
                    direction = Direction.UP;
                }
                case 1 -> {
                    v0 = anchor;
                    v1 = anchor + 1.0F;
                    u0 = min;
                    x1 = min;
                    x2 = u1 = max + 1.0F;
                    y1 = anchor + 1.0F;
                    y2 = anchor + 1.0F;
                    direction = Direction.DOWN;
                }
                case 2 -> {
                    u0 = anchor;
                    x1 = anchor;
                    x2 = anchor;
                    u1 = anchor + 1.0F;
                    v1 = min;
                    y1 = min;
                    y2 = v0 = max + 1.0F;
                    direction = Direction.EAST;
                }
                case 3 -> {
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

            // Preserve ItemModelGenerator's exact float operation order.
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

            // Inline NeoForge ClientHooks.fixItemModelSeams for one-face generated side elements.
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

            // Trimmable Tools 2.0.5 mixes into ItemModelGenerator#createSideElements and expands only
            // its side geometry by 0.01. The direct path bypasses that temporary element graph, so
            // reproduce the exact post-seam transformed delta for its own sprite namespace.
            if (TRIMMABLE_TOOLS_NAMESPACE.equals(sprite.contents().name().getNamespace())) {
                float transformedDelta = (1.0F - seamExpand) * TRIMMABLE_TOOLS_SIDE_EXPANSION;
                switch (direction) {
                    case UP -> {
                        y1 = Mth.clamp(y1 + transformedDelta, 0.0F, 16.0F);
                        y2 = Mth.clamp(y2 + transformedDelta, 0.0F, 16.0F);
                    }
                    case DOWN -> {
                        y1 = Mth.clamp(y1 - transformedDelta, 0.0F, 16.0F);
                        y2 = Mth.clamp(y2 - transformedDelta, 0.0F, 16.0F);
                    }
                    case EAST -> {
                        x1 = Mth.clamp(x1 - transformedDelta, 0.0F, 16.0F);
                        x2 = Mth.clamp(x2 - transformedDelta, 0.0F, 16.0F);
                    }
                    case WEST -> {
                        x1 = Mth.clamp(x1 + transformedDelta, 0.0F, 16.0F);
                        x2 = Mth.clamp(x2 + transformedDelta, 0.0F, 16.0F);
                    }
                    default -> {
                    }
                }
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

    private static Direction[] computeFrontBackOrder() {
        // ItemModelGenerator stores the front/back faces in a plain HashMap. Probe the same key type
        // once so ordering follows the JVM's actual Direction hash buckets instead of assuming insertion order.
        Map<Direction, Boolean> orderProbe = new HashMap<>();
        orderProbe.put(Direction.SOUTH, Boolean.TRUE);
        orderProbe.put(Direction.NORTH, Boolean.TRUE);
        return orderProbe.keySet().toArray(Direction[]::new);
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
