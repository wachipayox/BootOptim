package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reload-scoped cache for CITResewn's read-only inspection of base item models.
 *
 * <p>CITResewn parses the same {@code models/item/*.json} once per matching CIT.
 * The returned model is only inspected for its override list by that path; custom
 * CIT assets still take the original loader path. The cache is cleared at every
 * ModelBakery construction, which is the reload boundary for these assets.</p>
 */
public final class CitResewnItemModelCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENABLE_PROPERTY = "boot_optim.citresewnItemModelCache";
    private static final ConcurrentHashMap<ResourceLocation, BlockModel> BASE_MODELS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ResourceLocation> PENDING_ID = new ThreadLocal<>();
    private static final LongAdder REQUESTS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder PARSED_BYTES_UNKNOWN = new LongAdder();

    private CitResewnItemModelCache() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    /** Called by the optional TypeItem redirect before the Reader is created. */
    public static Optional<Resource> remember(ResourceManager manager, ResourceLocation id, Optional<Resource> result) {
        if (enabled() && isBaseItemModel(id) && result.isPresent()) {
            PENDING_ID.set(id);
        } else {
            PENDING_ID.remove();
        }
        return result;
    }

    /**
     * Redirect target for the specific BlockModel.fromStream calls in TypeItem.
     * A cache hit deliberately leaves the Reader for the caller's try-with-resources
     * to close, but does not consume it.
     */
    public static BlockModel parse(Reader reader) {
        ResourceLocation id = PENDING_ID.get();
        PENDING_ID.remove();
        if (!enabled() || id == null) {
            return BlockModel.fromStream(reader);
        }

        REQUESTS.increment();
        BlockModel cached = BASE_MODELS.get(id);
        if (cached != null) {
            HITS.increment();
            return cached;
        }

        MISSES.increment();
        BlockModel parsed = BlockModel.fromStream(reader);
        BlockModel existing = BASE_MODELS.putIfAbsent(id, parsed);
        return existing == null ? parsed : existing;
    }

    /** Must run before CITResewn's TypeItem constructor hook on each resource reload. */
    public static void beginReload() {
        BASE_MODELS.clear();
        PENDING_ID.remove();
        REQUESTS.reset();
        HITS.reset();
        MISSES.reset();
        PARSED_BYTES_UNKNOWN.reset();
    }

    /**
     * Emits one compact diagnostic line when startup profiling is enabled. It is
     * intentionally silent during ordinary launches.
     */
    public static void report() {
        if (!Boolean.getBoolean("boot_optim.profileStartup") &&
                !Boolean.getBoolean("boot_optim.benchmark.exitOnTitle")) {
            return;
        }
        long requests = REQUESTS.sum();
        if (requests == 0L) {
            return;
        }
        LOGGER.info("BOOTOPTIM_CITRESEWN_BASE_MODEL_CACHE requests={} hits={} misses={} hit_rate_percent={} entries={}",
                requests, HITS.sum(), MISSES.sum(), (HITS.sum() * 100L) / requests, BASE_MODELS.size());
    }

    private static boolean isBaseItemModel(ResourceLocation id) {
        if (id == null) return false;
        String path = id.getPath();
        return path.startsWith("models/item/") && path.endsWith(".json");
    }
}
