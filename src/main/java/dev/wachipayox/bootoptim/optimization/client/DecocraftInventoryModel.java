package dev.wachipayox.bootoptim.optimization.client;

import com.google.gson.JsonParseException;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inventory view of a decoration's canonical cached block model: the item renders the 3D block
 * geometry that Decocraft already baked (X0_Y0, the model the block itself uses), scaled to fit the
 * slot, without cloning any quad. Only created through {@link DecocraftInventoryPatch}; the loader
 * accepts only {@code decocraft:block/*} references.
 */
public final class DecocraftInventoryModel extends BakedModelWrapper<BakedModel> {
    public static final ResourceLocation LOADER_ID = ResourceLocation.fromNamespaceAndPath("boot_optim", "deco_inventory");
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftInventory");
    private static final AtomicLong WRAPPERS = new AtomicLong();

    private final float cx;
    private final float cy;
    private final float cz;
    private final float fit;

    private DecocraftInventoryModel(BakedModel base) {
        super(base);
        float minX = 0;
        float minY = 0;
        float minZ = 0;
        float maxX = 1;
        float maxY = 1;
        float maxZ = 1;
        List<BakedQuad> quads = base.getQuads(null, null, RandomSource.create(42));
        for (BakedQuad quad : quads) {
            int[] v = quad.getVertices();
            int stride = v.length / 4;
            if (stride < 3) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                float x = Float.intBitsToFloat(v[i * stride]);
                float y = Float.intBitsToFloat(v[i * stride + 1]);
                float z = Float.intBitsToFloat(v[i * stride + 2]);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        cz = (minZ + maxZ) / 2;
        fit = .65f / Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this);
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean left) {
        if (context == ItemDisplayContext.GUI) {
            pose.mulPose(Axis.XP.rotationDegrees(30));
            pose.mulPose(Axis.YP.rotationDegrees(225));
        }
        float scale = context == ItemDisplayContext.GROUND ? fit * .5f : fit;
        pose.scale(scale, scale, scale);
        pose.translate(.5f - cx, .5f - cy, .5f - cz);
        return this;
    }

    public static final IGeometryLoader<Geometry> LOADER = (json, context) -> {
        ResourceLocation block = ResourceLocation.parse(json.get("block").getAsString());
        if (!block.getNamespace().equals("decocraft") || !block.getPath().startsWith("block/")) {
            throw new JsonParseException("invalid inventory block reference");
        }
        return new Geometry(block);
    };

    public record Geometry(ResourceLocation block) implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                Function<Material, TextureAtlasSprite> sprites, ModelState state, ItemOverrides overrides) {
            BakedModel cached = baker.bake(block, BlockModelRotation.X0_Y0);
            DecocraftInventoryModel view = new DecocraftInventoryModel(cached);
            long count = WRAPPERS.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                LOGGER.info("BOOTOPTIM_DECOCRAFT_INVENTORY block_backed_inventory_views={}", count);
            }
            return view;
        }
    }
}
