package dev.wachipayox.bootoptim.optimization.client;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBakery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Temporary route probe for the direct generated-item experiment. */
public final class GeneratedItemBakeRouteProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/GeneratedItemDirect");
    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder CUSTOM = new LongAdder();
    private static final LongAdder GENERATION = new LongAdder();
    private static final LongAdder BLOCK_ENTITY = new LongAdder();
    private static final LongAdder NORMAL = new LongAdder();

    private GeneratedItemBakeRouteProbe() {
    }

    /** Mirrors UnbakedGeometryHelper's route precedence and returns true only for the strict generation-marker path. */
    public static boolean recordAndIsGenerated(BlockModel blockModel) {
        TOTAL.increment();
        if (blockModel.customData.getCustomGeometry() != null) {
            CUSTOM.increment();
            return false;
        }
        BlockModel root = blockModel.getRootModel();
        if (root == ModelBakery.GENERATION_MARKER) {
            GENERATION.increment();
            return true;
        }
        if (root == ModelBakery.BLOCK_ENTITY_MARKER) {
            BLOCK_ENTITY.increment();
        } else {
            NORMAL.increment();
        }
        return false;
    }

    public static void report() {
        LOGGER.info(
                "BOOTOPTIM_GENERATED_ITEM_ROUTE summary=model_bake_complete total={} custom={} generation={} block_entity={} normal={}",
                TOTAL.sum(), CUSTOM.sum(), GENERATION.sum(), BLOCK_ENTITY.sum(), NORMAL.sum());
    }
}
