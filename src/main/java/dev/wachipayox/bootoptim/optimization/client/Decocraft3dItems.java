package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.block.model.BlockModel;
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
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "boot_optim.decocraft3dItems.enabled",
            System.getProperty("boot_optim.decocraft3dItems", "true")));
    private static final String ENABLE_SOURCE = System.getProperty("boot_optim.decocraft3dItems.source", "default");
    private static final Set<ResourceLocation> KEEP_TEXTURES = loadKeepTextures();
    private static final boolean KEEP_LIST_VALID = KEEP_TEXTURES.size() == 63;
    private static final Set<Object> BLOCK_ATLAS_LISTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> FILTERING_BLOCKS_ATLAS = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean();

    private static volatile Boolean compatible;
    private static volatile String detectedVersion = "unknown";

    private Decocraft3dItems() {}

    public static void markAtlasList(ResourceLocation atlasInfo, Object spriteSourceList) {
        if (spriteSourceList == null || !BLOCKS_ATLAS_INFO.equals(atlasInfo)) {
            return;
        }
        synchronized (BLOCK_ATLAS_LISTS) {
            BLOCK_ATLAS_LISTS.add(spriteSourceList);
        }
    }

    public static void beginAtlasList(Object spriteSourceList) {
        if (!isActive()) {
            FILTERING_BLOCKS_ATLAS.set(false);
            return;
        }
        synchronized (BLOCK_ATLAS_LISTS) {
            FILTERING_BLOCKS_ATLAS.set(BLOCK_ATLAS_LISTS.contains(spriteSourceList));
        }
    }

    public static void endAtlasList() {
        FILTERING_BLOCKS_ATLAS.remove();
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
                "BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=models enabled=true version={} item_models={} remapped={} keep={} guard_rejected={} missing_parsed_block={} elapsed_ms={}",
                detectedVersion,
                itemModels,
                remapped,
                keep,
                guardRejected,
                missingParsedBlock,
                millis(System.nanoTime() - started));

        return replacement == null ? original : Map.copyOf(replacement);
    }

    /**
     * Filters the selected resources returned by DirectoryLister only while minecraft:blocks is being
     * enumerated. This happens before SpriteSupplier creation and therefore before Resource.open/PNG decode.
     */
    public static Map<ResourceLocation, Resource> filterDirectoryResources(
            ResourceManager resourceManager,
            String sourcePath,
            Map<ResourceLocation, Resource> original) {
        if (!Boolean.TRUE.equals(FILTERING_BLOCKS_ATLAS.get()) || !"item".equals(sourcePath) || !isActive()) {
            return original;
        }

        long started = System.nanoTime();
        HashMap<ResourceLocation, Resource> filtered = null;
        int decocraftItemPngs = 0;
        int removed = 0;
        int keep = 0;
        int overriddenTexture = 0;
        int guardRejected = 0;

        for (Map.Entry<ResourceLocation, Resource> entry : original.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            if (!MOD_ID.equals(fileId.getNamespace())) {
                continue;
            }
            String path = fileId.getPath();
            if (!path.startsWith("textures/item/") || !path.endsWith(".png")) {
                continue;
            }
            decocraftItemPngs++;
            String itemPath = path.substring("textures/item/".length(), path.length() - ".png".length());
            ResourceLocation spriteId = decocraft("item/" + itemPath);
            if (KEEP_TEXTURES.contains(spriteId)) {
                keep++;
                continue;
            }
            if (!SOURCE_PACK_ID.equals(entry.getValue().sourcePackId())) {
                overriddenTexture++;
                continue;
            }
            if (!hasAuditedBaseResources(resourceManager, itemPath, entry.getValue())) {
                guardRejected++;
                continue;
            }

            if (filtered == null) {
                filtered = new HashMap<>(original);
            }
            filtered.remove(fileId);
            removed++;
        }

        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=atlas enabled=true version={} item_pngs={} removed={} keep={} overridden_texture={} guard_rejected={} elapsed_ms={}",
                detectedVersion,
                decocraftItemPngs,
                removed,
                keep,
                overriddenTexture,
                guardRejected,
                millis(System.nanoTime() - started));

        return filtered == null ? original : filtered;
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
}
