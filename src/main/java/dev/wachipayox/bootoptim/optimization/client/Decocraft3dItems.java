package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

/**
 * Optional Decocraft 3.0.11 startup tradeoff: use the already-required 3D block geometry for
 * inventory/hand items and omit the corresponding prerendered 2D item PNGs from the blocks atlas.
 *
 * <p>This deliberately changes item appearance and render cost. It is enabled by default by project
 * choice, but every resource-level decision fails open when a resource pack overrides the Decocraft
 * item model, block model or item texture. The exact 3.0.11 version and the audited 63-texture keep
 * list are also hard guards.</p>
 */
public final class Decocraft3dItems {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "decocraft";
    private static final String EXPECTED_VERSION = "3.0.11";
    private static final String SOURCE_PACK_ID = "mod/decocraft";
    private static final String KEEP_LIST_RESOURCE = "/bootoptim/decocraft-3.0.11-item-textures-keep.txt";
    private static final ResourceLocation BLOCKS_ATLAS_INFO = ResourceLocation.withDefaultNamespace("blocks");
    private static final ResourceLocation GENERATED_PARENT = ResourceLocation.withDefaultNamespace("item/generated");
    private static final ResourceLocation BUILTIN_GENERATED_PARENT = ResourceLocation.withDefaultNamespace("builtin/generated");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "boot_optim.decocraft3dItems.enabled",
            System.getProperty("boot_optim.decocraft3dItems", "true")));
    private static final String ENABLE_SOURCE = System.getProperty("boot_optim.decocraft3dItems.source", "default");
    private static final Set<ResourceLocation> KEEP_TEXTURES = loadKeepTextures();
    private static final boolean KEEP_LIST_VALID = KEEP_TEXTURES.size() == 63;

    /** Weak because SpriteSourceList instances are reload-scoped and must not become a cache root. */
    private static final Set<Object> BLOCK_ATLAS_LISTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> WRAPPED_ATLAS_LISTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean();

    private static volatile Boolean compatible;
    private static volatile String detectedVersion = "unknown";

    private Decocraft3dItems() {}

    /** Marks only the SpriteSourceList created for {@code minecraft:blocks}. */
    public static void markAtlasList(ResourceLocation atlasInfo, Object spriteSourceList) {
        if (spriteSourceList == null || !BLOCKS_ATLAS_INFO.equals(atlasInfo)) {
            return;
        }
        synchronized (BLOCK_ATLAS_LISTS) {
            BLOCK_ATLAS_LISTS.add(spriteSourceList);
        }
    }

    /**
     * Replaces the blocks-atlas source list with wrappers that can see the selected {@link Resource}
     * before the stock {@link SpriteSource.Output} turns it into a SpriteSupplier.
     *
     * <p>Supplier-only additions are delegated unchanged because their backing resource cannot be
     * proven. This keeps custom atlas sources fail-open.</p>
     */
    public static List<SpriteSource> wrapAtlasSources(Object spriteSourceList, List<SpriteSource> original) {
        if (spriteSourceList == null || original == null) {
            return original;
        }

        boolean isBlocksAtlas;
        synchronized (BLOCK_ATLAS_LISTS) {
            isBlocksAtlas = BLOCK_ATLAS_LISTS.contains(spriteSourceList);
        }
        if (!isBlocksAtlas || !isActive()) {
            return original;
        }

        synchronized (WRAPPED_ATLAS_LISTS) {
            if (WRAPPED_ATLAS_LISTS.contains(spriteSourceList)) {
                return original;
            }
        }

        AtlasFilterStats stats = new AtlasFilterStats(original.size());
        ArrayList<SpriteSource> wrapped = new ArrayList<>(original.size());
        for (SpriteSource source : original) {
            wrapped.add(new FilteringSpriteSource(source, stats));
        }

        synchronized (WRAPPED_ATLAS_LISTS) {
            WRAPPED_ATLAS_LISTS.add(spriteSourceList);
        }
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=atlas_install enabled=true version={} sources={} keep_list={}",
                detectedVersion,
                original.size(),
                KEEP_TEXTURES.size());
        return wrapped;
    }

    /** Replaces exact Decocraft generated-item JSONs with a parent-only model pointing at the 3D block model. */
    public static Map<ResourceLocation, BlockModel> remapModels(
            ResourceManager resourceManager, Map<ResourceLocation, BlockModel> original) {
        if (!isActive()) {
            return original;
        }

        long started = System.nanoTime();
        HashMap<ResourceLocation, BlockModel> replacement = null;
        int itemModels = 0;
        int remapped = 0;
        int keep = 0;
        int shapeRejected = 0;
        int guardRejected = 0;
        int missingParsedBlock = 0;

        for (Map.Entry<ResourceLocation, BlockModel> entry : original.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            if (!MOD_ID.equals(fileId.getNamespace())) {
                continue;
            }
            String path = fileId.getPath();
            if (!path.startsWith("models/item/") || !path.endsWith(".json")) {
                continue;
            }
            itemModels++;
            String modelPath = path.substring("models/item/".length(), path.length() - ".json".length());
            ResourceLocation spriteId = decocraft("item/" + modelPath);
            if (KEEP_TEXTURES.contains(spriteId)) {
                keep++;
                continue;
            }

            BlockModel originalModel = entry.getValue();
            if (!isAuditedGeneratedItemShape(originalModel, spriteId)) {
                shapeRejected++;
                continue;
            }

            ResourceLocation blockFile = decocraft("models/block/" + modelPath + ".json");
            if (!original.containsKey(blockFile)) {
                missingParsedBlock++;
                continue;
            }
            if (!hasAuditedBaseResources(resourceManager, modelPath, null)) {
                guardRejected++;
                continue;
            }

            try {
                BlockModel synthetic = BlockModel.fromString("{\"parent\":\"decocraft:block/" + modelPath + "\"}");
                if (replacement == null) {
                    replacement = new HashMap<>(original);
                }
                replacement.put(fileId, synthetic);
                remapped++;
            } catch (RuntimeException unexpected) {
                guardRejected++;
            }
        }

        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=models enabled=true version={} item_models={} remapped={} keep={} shape_rejected={} guard_rejected={} missing_parsed_block={} elapsed_ms={}",
                detectedVersion,
                itemModels,
                remapped,
                keep,
                shapeRejected,
                guardRejected,
                missingParsedBlock,
                millis(System.nanoTime() - started));

        return replacement == null ? original : Map.copyOf(replacement);
    }

    private static boolean isAuditedGeneratedItemShape(BlockModel model, ResourceLocation expectedTexture) {
        if (model == null || !model.getOverrides().isEmpty() || !model.getElements().isEmpty()) {
            return false;
        }
        ResourceLocation parent = model.getParentLocation();
        if (!GENERATED_PARENT.equals(parent) && !BUILTIN_GENERATED_PARENT.equals(parent)) {
            return false;
        }
        try {
            Material layer0 = model.getMaterial("layer0");
            return layer0 != null && expectedTexture.equals(layer0.texture());
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    private static boolean shouldElideSprite(
            ResourceManager resourceManager,
            ResourceLocation spriteId,
            Resource selectedResource,
            AtlasFilterStats stats) {
        if (!isDecocraftItemSprite(spriteId)) {
            return false;
        }

        stats.resourceCandidates++;
        if (KEEP_TEXTURES.contains(spriteId)) {
            stats.keep++;
            return false;
        }
        if (selectedResource == null || !SOURCE_PACK_ID.equals(selectedResource.sourcePackId())) {
            stats.overriddenTexture++;
            return false;
        }

        String itemPath = spriteId.getPath().substring("item/".length());
        if (!hasAuditedBaseResources(resourceManager, itemPath, selectedResource)) {
            stats.guardRejected++;
            return false;
        }

        stats.removed++;
        return true;
    }

    private static boolean isDecocraftItemSprite(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith("item/");
    }

    private static boolean hasAuditedBaseResources(
            ResourceManager resourceManager, String itemPath, Resource alreadySelectedTexture) {
        ResourceLocation itemModel = decocraft("models/item/" + itemPath + ".json");
        ResourceLocation blockModel = decocraft("models/block/" + itemPath + ".json");
        ResourceLocation itemTexture = decocraft("textures/item/" + itemPath + ".png");
        return isFromDecocraft(resourceManager.getResource(itemModel))
                && isFromDecocraft(resourceManager.getResource(blockModel))
                && (alreadySelectedTexture != null
                        ? SOURCE_PACK_ID.equals(alreadySelectedTexture.sourcePackId())
                        : isFromDecocraft(resourceManager.getResource(itemTexture)));
    }

    private static boolean isFromDecocraft(Optional<Resource> resource) {
        return resource.isPresent() && SOURCE_PACK_ID.equals(resource.get().sourcePackId());
    }

    private static boolean isActive() {
        if (!ENABLED) {
            logStatus("disabled", "configured_false");
            return false;
        }
        if (!KEEP_LIST_VALID) {
            logStatus("disabled", "keep_list_invalid_" + KEEP_TEXTURES.size());
            return false;
        }
        if (!isCompatibleVersion()) {
            logStatus("skipped", "version_" + detectedVersion);
            return false;
        }
        logStatus("active", "compatible");
        return true;
    }

    private static boolean isCompatibleVersion() {
        Boolean cached = compatible;
        if (cached != null) {
            return cached;
        }
        synchronized (Decocraft3dItems.class) {
            cached = compatible;
            if (cached != null) {
                return cached;
            }
            try {
                ModList modList = ModList.get();
                if (modList == null) {
                    detectedVersion = "mod_list_unavailable";
                    compatible = false;
                    return false;
                }
                IModFileInfo file = modList.getModFileById(MOD_ID);
                if (file == null) {
                    detectedVersion = "missing";
                    compatible = false;
                    return false;
                }
                detectedVersion = file.getMods().stream()
                        .filter(info -> MOD_ID.equals(info.getModId()))
                        .map(info -> info.getVersion().toString())
                        .findFirst()
                        .orElse("unknown");
                compatible = EXPECTED_VERSION.equals(detectedVersion);
                return compatible;
            } catch (RuntimeException unexpected) {
                detectedVersion = "probe_failed_" + unexpected.getClass().getSimpleName();
                compatible = false;
                return false;
            }
        }
    }

    private static Set<ResourceLocation> loadKeepTextures() {
        HashSet<ResourceLocation> result = new HashSet<>();
        try (InputStream stream = Decocraft3dItems.class.getResourceAsStream(KEEP_LIST_RESOURCE)) {
            if (stream == null) {
                return Set.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    ResourceLocation id = ResourceLocation.tryParse(line);
                    if (id == null || !MOD_ID.equals(id.getNamespace()) || !id.getPath().startsWith("item/")) {
                        return Set.of();
                    }
                    result.add(id);
                }
            }
        } catch (Exception unexpected) {
            return Set.of();
        }
        return Set.copyOf(result);
    }

    private static ResourceLocation decocraft(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void logStatus(String status, String reason) {
        if (STATUS_LOGGED.compareAndSet(false, true)) {
            LOGGER.info(
                    "BOOTOPTIM_DECOCRAFT_3D_ITEMS status={} enabled={} source={} version={} keep_list={} reason={}",
                    status,
                    ENABLED,
                    ENABLE_SOURCE,
                    detectedVersion,
                    KEEP_TEXTURES.size(),
                    reason);
        }
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static final class FilteringSpriteSource implements SpriteSource {
        private final SpriteSource delegate;
        private final AtlasFilterStats stats;

        private FilteringSpriteSource(SpriteSource delegate, AtlasFilterStats stats) {
            this.delegate = delegate;
            this.stats = stats;
        }

        @Override
        public void run(ResourceManager resourceManager, SpriteSource.Output output) {
            try {
                delegate.run(resourceManager, new SpriteSource.Output() {
                    @Override
                    public void add(ResourceLocation id, Resource resource) {
                        if (!shouldElideSprite(resourceManager, id, resource, stats)) {
                            output.add(id, resource);
                        }
                    }

                    @Override
                    public void add(ResourceLocation id, SpriteSource.SpriteSupplier supplier) {
                        if (isDecocraftItemSprite(id) && !KEEP_TEXTURES.contains(id)) {
                            stats.unverifiedSupplierCandidates++;
                        }
                        output.add(id, supplier);
                    }

                    @Override
                    public void removeAll(java.util.function.Predicate<ResourceLocation> predicate) {
                        output.removeAll(predicate);
                    }
                });
            } finally {
                stats.sourceFinished();
            }
        }

        @Override
        public SpriteSourceType type() {
            return delegate.type();
        }
    }

    private static final class AtlasFilterStats {
        private final int totalSources;
        private int completedSources;
        private int resourceCandidates;
        private int removed;
        private int keep;
        private int overriddenTexture;
        private int guardRejected;
        private int unverifiedSupplierCandidates;

        private AtlasFilterStats(int totalSources) {
            this.totalSources = totalSources;
        }

        private void sourceFinished() {
            completedSources++;
            if (completedSources == totalSources) {
                LOGGER.info(
                        "BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=atlas enabled=true version={} sources={} resource_candidates={} removed={} keep={} overridden_texture={} guard_rejected={} unverified_supplier_candidates={}",
                        detectedVersion,
                        totalSources,
                        resourceCandidates,
                        removed,
                        keep,
                        overriddenTexture,
                        guardRejected,
                        unverifiedSupplierCandidates);
            }
        }
    }
}
