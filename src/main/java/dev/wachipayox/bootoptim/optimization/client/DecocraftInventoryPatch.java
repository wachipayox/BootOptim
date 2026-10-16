package dev.wachipayox.bootoptim.optimization.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional per-reload resource overlay (appearance change, default OFF, {@code
 * -Dboot_optim.decocraftInventoryBlockModels=true}). Decocraft item models that are a pure
 * {@code item/generated} icon of a decoration whose same-name block model is a Decocraft BBModel
 * from the same pack are replaced by {@code boot_optim:deco_inventory} models that render the
 * canonical block model ({@link DecocraftInventoryModel}). Icons that become unreferenced are
 * excluded from the block atlas by a {@code minecraft:filter} source; an icon still named by any
 * unchanged model stays. The original packs, the resource manager and the mod files are untouched;
 * anything unrecognised (tools, other shapes, other pack sources) keeps its original model.
 *
 * <p>Difference from the rejected PR #79 experiment ({@code decocraft-3d-item-sprite-elision-2026-09-02}):
 * #79 remapped parsed models to a parent-only block model gated on the exact Decocraft version and a
 * 63-entry keep-list, and elided sprites by intercepting {@code SpriteSource.Output.add}. This overlay
 * has no version or keep-list gate: it derives eligibility from the JSON shape and pack provenance of
 * each resource, renders through a wrapper around the cached canonical model, and excludes sprites
 * through the standard atlas filter source plus a reference scan. #79 showed no end-to-end startup
 * gain on the laptop and a hosted regression; this switch is offered for memory/appearance, not
 * startup time, and carries no time claim.</p>
 */
public final class DecocraftInventoryPatch implements ResourceManager {
    public static final String PROPERTY = "boot_optim.decocraftInventoryBlockModels";
    private static final String MECHANISM = "decocraft-inventory-block-models";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftInventory");
    private static final ResourceLocation ATLAS = ResourceLocation.parse("minecraft:atlases/blocks.json");
    private static final int MAX_CANDIDATES = 4096;
    private static final int MAX_JSON_CHARACTERS = 4 * 1024 * 1024;

    private final ResourceManager original;
    private final Map<ResourceLocation, Resource> replacements;
    private final Resource atlasFilter;

    private DecocraftInventoryPatch(ResourceManager original, Map<ResourceLocation, Resource> replacements, Resource filter) {
        this.original = original;
        this.replacements = Map.copyOf(replacements);
        this.atlasFilter = filter;
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static ResourceManager wrap(ResourceManager manager) {
        if (!enabled() || !ModList.get().isLoaded("decocraft")) {
            return manager;
        }
        try {
            Map<ResourceLocation, Resource> models = manager.listResources("models", id -> id.getPath().endsWith(".json"));
            Map<ResourceLocation, Resource> replacements = new HashMap<>();
            Set<String> excluded = new HashSet<>();
            Map<ResourceLocation, String> text = new HashMap<>();
            Resource source = null;
            for (var entry : models.entrySet()) {
                ResourceLocation id = entry.getKey();
                if (!id.getNamespace().equals("decocraft") || !id.getPath().startsWith("models/item/")) {
                    continue;
                }
                if (replacements.size() >= MAX_CANDIDATES) {
                    throw new IllegalStateException("candidate_limit");
                }
                String input = read(entry.getValue());
                text.put(id, input);
                JsonObject item = JsonParser.parseString(input).getAsJsonObject();
                if (!item.keySet().equals(Set.of("parent", "textures")) || !item.get("parent").getAsString().equals("item/generated")) {
                    continue;
                }
                JsonObject textures = item.getAsJsonObject("textures");
                if (!textures.keySet().equals(Set.of("layer0"))) {
                    continue;
                }
                String texture = textures.get("layer0").getAsString();
                if (!texture.startsWith("decocraft:item/")) {
                    continue;
                }
                String name = id.getPath().substring("models/item/".length(), id.getPath().length() - 5);
                Resource block = models.get(ResourceLocation.parse("decocraft:models/block/" + name + ".json"));
                if (block == null || !block.sourcePackId().equals(entry.getValue().sourcePackId())
                        || !block.sourcePackId().contains("decocraft")) {
                    continue;
                }
                JsonObject shape = JsonParser.parseString(read(block)).getAsJsonObject();
                if (!shape.has("loader") || !shape.get("loader").getAsString().equals("decocraft:bbmodel") || !shape.has("decoref")) {
                    continue;
                }
                Optional<Resource> image = manager.getResource(
                        ResourceLocation.parse("decocraft:textures/" + texture.substring("decocraft:".length()) + ".png"));
                if (image.isEmpty() || !image.get().sourcePackId().equals(block.sourcePackId())) {
                    continue;
                }
                JsonObject overlay = new JsonObject();
                overlay.addProperty("parent", "decocraft:block/" + name);
                overlay.addProperty("loader", DecocraftInventoryModel.LOADER_ID.toString());
                overlay.addProperty("block", "decocraft:block/" + name);
                replacements.put(id, resource(entry.getValue(), overlay.toString()));
                excluded.add(texture.substring("decocraft:".length()));
                source = entry.getValue();
            }
            // An icon still referenced by an unchanged JSON model must remain in the atlas.
            Pattern reference = Pattern.compile("decocraft:(item/[a-z0-9_./-]+)");
            for (var entry : models.entrySet()) {
                if (replacements.containsKey(entry.getKey())) {
                    continue;
                }
                String input = text.containsKey(entry.getKey()) ? text.get(entry.getKey()) : read(entry.getValue());
                if (input.contains("decocraft:item/")) {
                    var matcher = reference.matcher(input);
                    while (matcher.find()) {
                        excluded.remove(matcher.group(1));
                    }
                }
            }
            if (replacements.isEmpty() || excluded.isEmpty()) {
                CompatLog.fallback(MECHANISM, "decocraft", "no_eligible_resource_shapes");
                return manager;
            }
            JsonObject pattern = new JsonObject();
            pattern.addProperty("namespace", "decocraft");
            pattern.addProperty("path", excluded.stream().sorted().map(Pattern::quote)
                    .collect(Collectors.joining("|", "(?:", ")")));
            JsonObject filter = new JsonObject();
            filter.addProperty("type", "minecraft:filter");
            filter.add("pattern", pattern);
            JsonArray sources = new JsonArray();
            sources.add(filter);
            JsonObject atlas = new JsonObject();
            atlas.add("sources", sources);
            CompatLog.active(MECHANISM, "decocraft");
            LOGGER.info("BOOTOPTIM_DECOCRAFT_INVENTORY inventory_models={} excluded_icon_sprites={}",
                    replacements.size(), excluded.size());
            return new DecocraftInventoryPatch(manager, replacements, resource(source, atlas.toString()));
        } catch (Exception | LinkageError changed) {
            CompatLog.fallback(MECHANISM, "decocraft", "resource_contract_" + changed.getClass().getSimpleName());
            return manager;
        }
    }

    private static String read(Resource resource) throws IOException {
        try (Reader reader = resource.openAsReader()) {
            char[] chars = new char[4096];
            StringBuilder text = new StringBuilder();
            int n;
            while ((n = reader.read(chars)) != -1) {
                if (text.length() + n > MAX_JSON_CHARACTERS) {
                    throw new IOException("model_resource_limit");
                }
                text.append(chars, 0, n);
            }
            return text.toString();
        }
    }

    private static Resource resource(Resource original, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Resource(new OverlaySource(original.source()), () -> new ByteArrayInputStream(bytes));
    }

    @Override public Set<String> getNamespaces() { return original.getNamespaces(); }

    @Override public Optional<Resource> getResource(ResourceLocation id) {
        return replacements.containsKey(id) ? Optional.of(replacements.get(id)) : original.getResource(id);
    }

    @Override public List<Resource> getResourceStack(ResourceLocation id) {
        List<Resource> stack = new ArrayList<>(original.getResourceStack(id));
        if (id.equals(ATLAS)) {
            stack.add(atlasFilter);
        } else if (replacements.containsKey(id)) {
            stack.clear();
            stack.add(replacements.get(id));
        }
        return stack;
    }

    @Override public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, Resource> result = new HashMap<>(original.listResources(path, filter));
        result.replaceAll((id, resource) -> replacements.getOrDefault(id, resource));
        return result;
    }

    @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, List<Resource>> result = new HashMap<>(original.listResourceStacks(path, filter));
        result.replaceAll((id, stack) -> replacements.containsKey(id) ? List.of(replacements.get(id)) : stack);
        return result;
    }

    @Override public Stream<PackResources> listPacks() { return original.listPacks().map(PackView::new); }

    /** Synthetic source for an overlay resource; it never owns or closes the original pack. */
    private static final class OverlaySource implements PackResources {
        final PackResources pack;

        OverlaySource(PackResources pack) { this.pack = pack; }

        @Override public IoSupplier<InputStream> getRootResource(String... path) { return pack.getRootResource(path); }

        @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) { return pack.getResource(type, id); }

        @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            pack.listResources(type, namespace, path, output);
        }

        @Override public Set<String> getNamespaces(PackType type) { return pack.getNamespaces(type); }

        @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
            return pack.getMetadataSection(serializer);
        }

        @Override public PackLocationInfo location() {
            return new PackLocationInfo("boot_optim/deco-inventory-overlay",
                    Component.literal("Decocraft inventory overlay"), PackSource.BUILT_IN, Optional.empty());
        }

        @Override public void close() {}
    }

    private final class PackView implements PackResources {
        private final PackResources pack;

        PackView(PackResources pack) { this.pack = pack; }

        @Override public IoSupplier<InputStream> getRootResource(String... path) { return pack.getRootResource(path); }

        private Resource replacement(ResourceLocation id) {
            Resource r = replacements.get(id);
            return r != null && r.source() instanceof OverlaySource overlay && overlay.pack.packId().equals(pack.packId()) ? r : null;
        }

        @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
            Resource r = type == PackType.CLIENT_RESOURCES ? replacement(id) : null;
            return r == null ? pack.getResource(type, id) : r::open;
        }

        @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            pack.listResources(type, namespace, path, (id, supplier) -> {
                Resource r = type == PackType.CLIENT_RESOURCES ? replacement(id) : null;
                output.accept(id, r == null ? supplier : r::open);
            });
        }

        @Override public Set<String> getNamespaces(PackType type) { return pack.getNamespaces(type); }

        @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
            return pack.getMetadataSection(serializer);
        }

        @Override public PackLocationInfo location() { return pack.location(); }

        @Override public void close() { pack.close(); }
    }
}
