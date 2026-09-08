package dev.wachipayox.bootoptim.replay.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.ElementsModel;
import org.joml.Vector3f;

/**
 * Headless class-level replay for the real Minecraft/NeoForge 1.21.1 model path.
 *
 * <p>The only JSON parsing performed by this class outside BlockModel is manifest handling. Model
 * bytes are always deserialized through the runtime BlockModel.fromStream(Reader) entry point. Parent
 * resolution invokes the runtime resolveParents(Function) method. Material resolution calls the real
 * BlockModel#getMaterial. The ordinary-elements lowering measurement constructs NeoForge's real
 * ElementsModel from the resolved BlockModel element list.</p>
 *
 * <p>This harness intentionally stops before TextureAtlasSprite/FaceBakery. Those fields are named in
 * the digest capability boundary instead of being silently treated as equivalent.</p>
 */
public final class ModelClassReplay {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson CANONICAL_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ThreadMetrics THREAD_METRICS = new ThreadMetrics();

    private ModelClassReplay() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        Path manifest = null;
        Path output = null;
        for (int i = 0; i < args.length; i++) {
            if ("--manifest".equals(args[i]) && i + 1 < args.length) manifest = Path.of(args[++i]);
            else if ("--output-dir".equals(args[i]) && i + 1 < args.length) output = Path.of(args[++i]);
        }
        if (manifest == null || output == null) {
            throw new IllegalArgumentException("usage: --manifest <manifest.json> --output-dir <dir>");
        }
        runManifest(manifest, output);
    }

    private static void runManifest(Path manifestPath, Path outputDir) throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        Path fixtureRoot = manifestPath.toAbsolutePath().getParent();
        Map<String, Source> sources = new TreeMap<>();
        for (JsonElement element : manifest.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            byte[] bytes = Files.readAllBytes(fixtureRoot.resolve(entry.get("content_path").getAsString()));
            JsonObject winner = entry.getAsJsonObject("winner");
            sources.put(id, new Source(id, bytes,
                    winner.get("source_id").getAsString(), winner.get("entry").getAsString(),
                    entry.get("sha256").getAsString(), entry.getAsJsonArray("shadowed")));
        }
        List<String> roots = new ArrayList<>();
        manifest.getAsJsonArray("roots").forEach(value -> roots.add(value.getAsString()));
        roots.sort(String::compareTo);

        ReplayRun stock1 = execute("stock-1", sources, roots);
        ReplayRun stock2 = execute("stock-2", sources, roots);
        ReplayRun identity = execute("identity-candidate", sources, roots);
        Diff stockRepeat = diff(stock1.semantic, stock2.semantic, "$", true);
        Diff identityDiff = diff(stock1.semantic, identity.semantic, "$", true);

        JsonObject fault = stock1.semantic.deepCopy();
        String injectedPath = injectSemanticFault(fault);
        Diff faultDiff = diff(stock1.semantic, fault, "$", true);
        if (stockRepeat != null) throw new IllegalStateException("stock digest is not reproducible: " + stockRepeat);
        if (identityDiff != null) throw new IllegalStateException("identity candidate diverged: " + identityDiff);
        if (faultDiff == null) throw new IllegalStateException("fault probe was not detected");

        Files.createDirectories(outputDir);
        JsonObject result = new JsonObject();
        result.addProperty("schema", 1);
        result.addProperty("origin", "hosted exact-pack class replay; not TTMM");
        result.addProperty("fixture_exact_pack_sha256", manifest.get("exact_pack_sha256").getAsString());
        result.add("runtime", runtimeInfo());
        result.add("capability_boundary", capabilityBoundary());
        result.add("stock_1", stock1.toJson());
        result.add("stock_2", stock2.toJson());
        result.add("identity_candidate", identity.toJson());
        result.addProperty("stock_repeat_equal", true);
        result.addProperty("identity_candidate_equal", true);
        result.addProperty("semantic_sha256", sha256(CANONICAL_GSON.toJson(stock1.semantic).getBytes(StandardCharsets.UTF_8)));
        result.addProperty("fault_probe_injected_at", injectedPath);
        result.addProperty("fault_probe_detected", true);
        result.addProperty("fault_probe_diff", faultDiff.toString());
        result.add("semantic", stock1.semantic);
        Files.writeString(outputDir.resolve("replay.json"), GSON.toJson(result) + "\n");
        writeCsv(outputDir.resolve("phases.csv"), List.of(stock1, stock2, identity));
        Files.writeString(outputDir.resolve("summary.md"), summary(result, stock1, stock2, identity));
        Files.writeString(outputDir.resolve("provenance.json"), GSON.toJson(provenance(manifest)) + "\n");
        System.out.println("MODEL_CLASS_REPLAY semantic_sha256=" + result.get("semantic_sha256").getAsString()
                + " roots=" + roots.size() + " models=" + stock1.models.size()
                + " stock_repeat_equal=true identity_candidate_equal=true fault_probe_detected=true");
    }

    private static ReplayRun execute(String mode, Map<String, Source> originalSources, List<String> roots) throws Exception {
        Map<String, Source> sources = new TreeMap<>(originalSources);
        Map<String, BlockModel> models = new TreeMap<>();
        Map<BlockModel, String> idsByIdentity = new IdentityHashMap<>();
        List<Event> events = new ArrayList<>();
        Map<String, PhaseMetric> phases = new LinkedHashMap<>();

        PhaseMeasurement parsePhase = THREAD_METRICS.start();
        for (Source source : sources.values()) {
            try {
                BlockModel model = parseReal(source.bytes);
                models.put(source.id, model);
                idsByIdentity.put(model, source.id);
            } catch (Throwable throwable) {
                events.add(Event.error("parse", source.id, throwable));
            }
        }
        phases.put("real_blockmodel_parse", parsePhase.finish(models.size()));

        Map<String, String> requestedParents = new TreeMap<>();
        for (Map.Entry<String, BlockModel> entry : models.entrySet()) {
            Object parentLocation = readProperty(entry.getValue(), "getParentLocation", "parentLocation");
            if (parentLocation != null) requestedParents.put(entry.getKey(), parentLocation.toString());
        }

        PhaseMeasurement parentPhase = THREAD_METRICS.start();
        int resolvedCalls = 0;
        List<String> work = new ArrayList<>(models.keySet());
        for (int index = 0; index < work.size(); index++) {
            String id = work.get(index);
            BlockModel model = models.get(id);
            if (model == null) continue;
            try {
                invokeResolveParents(model, location -> {
                    String parentId = location.toString();
                    BlockModel existing = models.get(parentId);
                    if (existing != null) return existing;
                    Source source = sources.get(parentId);
                    if (source != null) {
                        try {
                            BlockModel parsed = parseReal(source.bytes);
                            models.put(parentId, parsed);
                            idsByIdentity.put(parsed, parentId);
                            if (!work.contains(parentId)) work.add(parentId);
                            return parsed;
                        } catch (Throwable throwable) {
                            events.add(Event.error("parent_parse", parentId, throwable));
                            return null;
                        }
                    }
                    try {
                        Source classpath = classpathSource(parentId);
                        if (classpath == null) {
                            events.add(new Event("fallback", "parent_missing", id, parentId, null));
                            return null;
                        }
                        BlockModel parsed = parseReal(classpath.bytes);
                        sources.put(parentId, classpath);
                        models.put(parentId, parsed);
                        idsByIdentity.put(parsed, parentId);
                        if (!work.contains(parentId)) work.add(parentId);
                        return parsed;
                    } catch (Throwable throwable) {
                        events.add(Event.error("classpath_parent_parse", parentId, throwable));
                        return null;
                    }
                });
                resolvedCalls++;
            } catch (Throwable throwable) {
                events.add(Event.error("parent_resolution", id, throwable));
            }
        }
        phases.put("real_parent_resolution", parentPhase.finish(resolvedCalls));

        Map<BlockElementFace, Material> materials = new IdentityHashMap<>();
        PhaseMeasurement texturePhase = THREAD_METRICS.start();
        int materialLookups = 0;
        for (BlockModel model : models.values()) {
            try {
                for (BlockElement blockElement : model.getElements()) {
                    for (BlockElementFace face : blockElement.faces.values()) {
                        Material material = model.getMaterial(face.texture());
                        materials.put(face, material);
                        materialLookups++;
                    }
                }
            } catch (Throwable throwable) {
                events.add(Event.error("texture_chain_material_lookup", idsByIdentity.get(model), throwable));
            }
        }
        phases.put("real_texture_chain_material_lookup", texturePhase.finish(materialLookups));

        PhaseMeasurement loweringPhase = THREAD_METRICS.start();
        int loweredModels = 0;
        for (BlockModel model : models.values()) {
            try {
                if (!model.getElements().isEmpty()) {
                    // This is the real NeoForge ordinary-elements geometry constructor. It does not
                    // touch sprites/GL and is therefore safe in the headless replay.
                    new ElementsModel(model.getElements());
                    loweredModels++;
                }
            } catch (Throwable throwable) {
                events.add(Event.error("ordinary_elements_lowering", idsByIdentity.get(model), throwable));
            }
        }
        phases.put("real_elementsmodel_construction", loweringPhase.finish(loweredModels));

        JsonObject semantic = canonicalSemantic(sources, models, idsByIdentity, requestedParents, materials, events, roots);
        return new ReplayRun(mode, models, phases, semantic, events);
    }

    private static BlockModel parseReal(byte[] bytes) throws Throwable {
        Method method = Arrays.stream(BlockModel.class.getMethods())
                .filter(candidate -> Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> candidate.getName().equals("fromStream"))
                .filter(candidate -> candidate.getParameterCount() == 1 && Reader.class.isAssignableFrom(candidate.getParameterTypes()[0]))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BlockModel.fromStream(Reader)"));
        try (Reader reader = new StringReader(new String(bytes, StandardCharsets.UTF_8))) {
            try {
                return (BlockModel) method.invoke(null, reader);
            } catch (InvocationTargetException exception) {
                throw exception.getCause() == null ? exception : exception.getCause();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void invokeResolveParents(BlockModel model, Function<ResourceLocation, UnbakedModel> resolver) throws Throwable {
        Method method = Arrays.stream(model.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals("resolveParents"))
                .filter(candidate -> candidate.getParameterCount() == 1
                        && Function.class.isAssignableFrom(candidate.getParameterTypes()[0]))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BlockModel.resolveParents(Function)"));
        try {
            method.invoke(model, resolver);
        } catch (InvocationTargetException exception) {
            throw exception.getCause() == null ? exception : exception.getCause();
        }
    }

    private static Source classpathSource(String id) throws IOException {
        int colon = id.indexOf(':');
        String namespace = colon >= 0 ? id.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        String resource = "assets/" + namespace + "/models/" + path + ".json";
        try (var stream = ModelClassReplay.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) return null;
            byte[] bytes = stream.readAllBytes();
            return new Source(id, bytes, "runtime-classpath", resource, sha256(bytes), new JsonArray());
        }
    }

    private static JsonObject canonicalSemantic(Map<String, Source> sources, Map<String, BlockModel> models,
                                                Map<BlockModel, String> idsByIdentity, Map<String, String> requestedParents,
                                                Map<BlockElementFace, Material> materials, List<Event> events,
                                                List<String> roots) {
        JsonObject root = new JsonObject();
        JsonArray rootIds = new JsonArray();
        roots.stream().sorted().forEach(rootIds::add);
        root.add("roots", rootIds);
        Map<String, Integer> fanout = new TreeMap<>();
        requestedParents.values().forEach(parent -> fanout.merge(parent, 1, Integer::sum));

        JsonArray modelArray = new JsonArray();
        for (Map.Entry<String, BlockModel> entry : models.entrySet()) {
            String id = entry.getKey();
            BlockModel model = entry.getValue();
            Source source = sources.get(id);
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            if (source != null) {
                json.addProperty("source_id", source.sourceId);
                json.addProperty("source_entry", source.entry);
                json.addProperty("source_sha256", source.sha256);
                json.addProperty("shadowed_count", source.shadowed.size());
            }
            addNullableString(json, "requested_parent", requestedParents.get(id));
            json.addProperty("fan_out", fanout.getOrDefault(id, 0));
            json.add("resolved_parent_chain", parentChain(model, idsByIdentity));
            Object gui = readProperty(model, "getGuiLight", "guiLight");
            addNullableString(json, "gui_light", stableScalar(gui));
            Object ao = readProperty(model, "hasAmbientOcclusion", "ambientOcclusion");
            json.add("ambient_occlusion", primitiveJson(ao));
            json.add("transforms", canonicalTransforms(readProperty(model, "getTransforms", "transforms")));

            JsonArray elements = new JsonArray();
            int elementIndex = 0;
            for (BlockElement element : model.getElements()) {
                JsonObject elementJson = new JsonObject();
                elementJson.addProperty("index", elementIndex++);
                elementJson.add("from_bits", vectorBits(element.from));
                elementJson.add("to_bits", vectorBits(element.to));
                elementJson.addProperty("shade", element.shade);
                elementJson.add("rotation", canonicalRotation(element.rotation));
                elementJson.add("light_emission", primitiveJson(readProperty(element, "lightEmission", "lightEmission")));
                elementJson.add("extra_face_data", canonicalSimple(readProperty(element, "getFaceData", "faceData")));
                JsonArray faces = new JsonArray();
                int faceIndex = 0;
                for (Map.Entry<?, BlockElementFace> faceEntry : element.faces.entrySet()) {
                    BlockElementFace face = faceEntry.getValue();
                    JsonObject faceJson = new JsonObject();
                    faceJson.addProperty("index", faceIndex++);
                    faceJson.addProperty("direction", String.valueOf(faceEntry.getKey()));
                    faceJson.addProperty("texture_ref", face.texture());
                    Object cull = face.cullForDirection();
                    addNullableString(faceJson, "cull", stableScalar(cull));
                    faceJson.add("tint_index", primitiveJson(readProperty(face, "tintIndex", "tintIndex")));
                    faceJson.add("uv", canonicalUv(readProperty(face, "uv", "uv")));
                    faceJson.add("extra_face_data", canonicalSimple(face.faceData()));
                    faceJson.add("resolved_material", canonicalMaterial(materials.get(face)));
                    faces.add(faceJson);
                }
                elementJson.add("faces", faces);
                elements.add(elementJson);
            }
            json.add("elements", elements);
            modelArray.add(json);
        }
        root.add("models", modelArray);
        JsonArray eventArray = new JsonArray();
        events.stream().sorted(Comparator.comparing(Event::sortKey)).forEach(event -> eventArray.add(event.toSemanticJson()));
        root.add("fallbacks_and_errors", eventArray);
        root.add("unobserved_fields", capabilityBoundary().getAsJsonArray("unobserved"));
        return root;
    }

    private static JsonArray parentChain(BlockModel start, Map<BlockModel, String> ids) {
        JsonArray chain = new JsonArray();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Object current = start;
        for (int depth = 0; depth < 128; depth++) {
            Object parent = readProperty(current, "parent", "parent");
            if (parent == null) break;
            if (seen.put(parent, Boolean.TRUE) != null) {
                chain.add("<cycle-observed>");
                break;
            }
            if (parent instanceof BlockModel blockModel) {
                String id = ids.get(blockModel);
                chain.add(id == null ? "<unmapped:" + parent.getClass().getName() + ">" : id);
            } else {
                chain.add("<non-blockmodel:" + parent.getClass().getName() + ">");
                break;
            }
            current = parent;
        }
        return chain;
    }

    private static JsonObject canonicalMaterial(Material material) {
        JsonObject json = new JsonObject();
        if (material == null) {
            json.addProperty("observed", false);
            return json;
        }
        json.addProperty("observed", true);
        addNullableString(json, "atlas", stableScalar(readProperty(material, "atlasLocation", "atlasLocation")));
        addNullableString(json, "texture", stableScalar(readProperty(material, "texture", "texture")));
        return json;
    }

    private static JsonElement canonicalRotation(Object rotation) {
        if (rotation == null) return JsonNull.INSTANCE;
        JsonObject json = new JsonObject();
        json.add("origin_bits", vectorBits(asVector(readProperty(rotation, "origin", "origin"))));
        addNullableString(json, "axis", stableScalar(readProperty(rotation, "axis", "axis")));
        json.add("angle_bits", floatBitsJson(readProperty(rotation, "angle", "angle")));
        json.add("rescale", primitiveJson(readProperty(rotation, "rescale", "rescale")));
        return json;
    }

    private static JsonElement canonicalUv(Object uv) {
        if (uv == null) return JsonNull.INSTANCE;
        JsonObject json = new JsonObject();
        Object uvs = readProperty(uv, "uvs", "uvs");
        JsonArray bits = new JsonArray();
        if (uvs instanceof float[] values) for (float value : values) bits.add(hex(Float.floatToRawIntBits(value)));
        json.add("uv_bits", bits);
        json.add("rotation", primitiveJson(readProperty(uv, "rotation", "rotation")));
        return json;
    }

    private static JsonElement canonicalTransforms(Object transforms) {
        if (transforms == null) return JsonNull.INSTANCE;
        JsonObject json = new JsonObject();
        String[] slots = {"thirdPersonLeftHand", "thirdPersonRightHand", "firstPersonLeftHand", "firstPersonRightHand",
                "head", "gui", "ground", "fixed"};
        boolean observed = false;
        for (String slot : slots) {
            Object transform = readProperty(transforms, slot, slot);
            if (transform != null) {
                observed = true;
                JsonObject item = new JsonObject();
                item.add("rotation_bits", vectorBits(asVector(readProperty(transform, "rotation", "rotation"))));
                item.add("translation_bits", vectorBits(asVector(readProperty(transform, "translation", "translation"))));
                item.add("scale_bits", vectorBits(asVector(readProperty(transform, "scale", "scale"))));
                item.add("right_rotation_bits", vectorBits(asVector(readProperty(transform, "rightRotation", "rightRotation"))));
                json.add(slot, item);
            }
        }
        json.addProperty("observed_known_slots", observed);
        return json;
    }

    private static JsonElement canonicalSimple(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof String || value.getClass().isEnum()) {
            return primitiveJson(value);
        }
        JsonObject json = new JsonObject();
        if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try { json.add(component.getName(), canonicalSimple(component.getAccessor().invoke(value))); }
                catch (ReflectiveOperationException exception) { json.add(component.getName(), JsonNull.INSTANCE); }
            }
            return json;
        }
        // Extended NeoForge metadata is not assumed equivalent when it cannot be structurally observed.
        json.addProperty("class", value.getClass().getName());
        json.addProperty("opaque", true);
        return json;
    }

    private static JsonElement primitiveJson(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean bool) return new com.google.gson.JsonPrimitive(bool);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return new com.google.gson.JsonPrimitive((Number) value);
        if (value instanceof Float f) return new com.google.gson.JsonPrimitive(hex(Float.floatToRawIntBits(f)));
        if (value instanceof Double d) return new com.google.gson.JsonPrimitive("0x" + Long.toHexString(Double.doubleToRawLongBits(d)));
        return new com.google.gson.JsonPrimitive(String.valueOf(value));
    }

    private static JsonElement floatBitsJson(Object value) {
        return value instanceof Number number
                ? new com.google.gson.JsonPrimitive(hex(Float.floatToRawIntBits(number.floatValue()))) : JsonNull.INSTANCE;
    }

    private static JsonArray vectorBits(Vector3f vector) {
        JsonArray array = new JsonArray();
        if (vector != null) {
            array.add(hex(Float.floatToRawIntBits(vector.x())));
            array.add(hex(Float.floatToRawIntBits(vector.y())));
            array.add(hex(Float.floatToRawIntBits(vector.z())));
        }
        return array;
    }

    private static Vector3f asVector(Object value) { return value instanceof Vector3f vector ? vector : null; }
    private static String hex(int value) { return String.format(Locale.ROOT, "0x%08x", value); }
    private static String stableScalar(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value.getClass().isEnum()
                || value instanceof ResourceLocation) return String.valueOf(value);
        return null;
    }

    private static Object readProperty(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    if (method.getParameterCount() == 0) {
                        method.setAccessible(true);
                        return method.invoke(target);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static JsonObject runtimeInfo() {
        JsonObject json = new JsonObject();
        json.addProperty("java", System.getProperty("java.version"));
        json.addProperty("block_model_class", BlockModel.class.getName());
        json.addProperty("parser_entrypoint", "real BlockModel.fromStream(Reader)");
        json.addProperty("parent_entrypoint", "real BlockModel.resolveParents(Function) via reflection");
        json.addProperty("material_entrypoint", "real BlockModel.getMaterial(String)");
        json.addProperty("ordinary_lowering_entrypoint", "real NeoForge ElementsModel(List<BlockElement>) constructor");
        JsonArray deserializers = new JsonArray();
        for (String name : List.of(
                "net.neoforged.neoforge.client.model.geometry.ExtendedBlockModelDeserializer",
                "net.neoforged.neoforge.client.model.ExtendedBlockModelDeserializer")) {
            try { deserializers.add(Class.forName(name, false, ModelClassReplay.class.getClassLoader()).getName()); }
            catch (ClassNotFoundException ignored) {}
        }
        json.add("extended_deserializer_classes_found", deserializers);
        Object gson = readStaticProperty(BlockModel.class, "GSON");
        if (gson instanceof Gson runtimeGson) {
            try { json.addProperty("block_model_gson_adapter", runtimeGson.getAdapter(BlockModel.class).getClass().getName()); }
            catch (RuntimeException ignored) {}
        }
        return json;
    }

    private static Object readStaticProperty(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static JsonObject capabilityBoundary() {
        JsonObject json = new JsonObject();
        JsonArray observed = new JsonArray();
        List.of("BlockModel real deserialization", "parent resolution/fallback result", "resolved parent chain",
                "element/face order", "raw IEEE float bits", "shade", "element rotation where exposed",
                "face texture reference", "tint/cull/UV where exposed", "BlockModel material atlas/texture where exposed",
                "known item transforms where exposed", "resource winner and shadowed provenance",
                "NeoForge ElementsModel construction").forEach(observed::add);
        JsonArray unobserved = new JsonArray();
        List.of("TextureAtlasSprite identity/current atlas generation", "FaceBakery output", "BakedQuad vertex ints",
                "final render-type buckets", "IModelBuilder callback output", "ModelState-dependent cull rotation",
                "NeoForge ModifyBakingResult/BakingCompleted callbacks", "GL/upload state").forEach(unobserved::add);
        json.add("observed", observed);
        json.add("unobserved", unobserved);
        json.addProperty("equivalence_claim", "observed pre-sprite/pre-bake structure only; never full baked-model equivalence");
        return json;
    }

    private static JsonObject provenance(JsonObject manifest) {
        JsonObject json = new JsonObject();
        for (String field : List.of("origin", "exact_pack_sha256", "selection_scope", "counts",
                "options_sha256", "selected_external_resource_packs_low_to_high",
                "missing_selected_external_packs", "resource_stack_low_to_high", "builtin_minecraft_fallback")) {
            if (manifest.has(field)) json.add(field, manifest.get(field));
        }
        json.add("entries", manifest.get("entries"));
        return json;
    }

    private static String injectSemanticFault(JsonObject semantic) {
        JsonArray models = semantic.getAsJsonArray("models");
        for (int mi = 0; mi < models.size(); mi++) {
            JsonArray elements = models.get(mi).getAsJsonObject().getAsJsonArray("elements");
            for (int ei = 0; ei < elements.size(); ei++) {
                JsonArray faces = elements.get(ei).getAsJsonObject().getAsJsonArray("faces");
                if (!faces.isEmpty()) {
                    faces.get(0).getAsJsonObject().addProperty("texture_ref", "#bootoptim_deliberate_fault");
                    return "$.models[" + mi + "].elements[" + ei + "].faces[0].texture_ref";
                }
            }
        }
        semantic.addProperty("deliberate_fault", true);
        return "$.deliberate_fault";
    }

    private static Diff diff(JsonElement left, JsonElement right, String path, boolean stopFirst) {
        if (left == null || right == null || left.isJsonNull() || right.isJsonNull())
            return Objects.equals(left, right) || left != null && right != null && left.isJsonNull() && right.isJsonNull()
                    ? null : new Diff(path, String.valueOf(left), String.valueOf(right));
        if (left.getClass() != right.getClass()) return new Diff(path, left.toString(), right.toString());
        if (left.isJsonPrimitive()) return left.equals(right) ? null : new Diff(path, left.toString(), right.toString());
        if (left.isJsonArray()) {
            JsonArray a = left.getAsJsonArray(), b = right.getAsJsonArray();
            if (a.size() != b.size()) return new Diff(path + ".length", String.valueOf(a.size()), String.valueOf(b.size()));
            for (int i = 0; i < a.size(); i++) { Diff d = diff(a.get(i), b.get(i), path + "[" + i + "]", stopFirst); if (d != null) return d; }
            return null;
        }
        JsonObject a = left.getAsJsonObject(), b = right.getAsJsonObject();
        if (!a.keySet().equals(b.keySet())) return new Diff(path + ".keys", a.keySet().toString(), b.keySet().toString());
        for (String key : new TreeMap<String, JsonElement>(a.asMap()).keySet()) {
            Diff d = diff(a.get(key), b.get(key), path + "." + key, stopFirst); if (d != null) return d;
        }
        return null;
    }

    private static void writeCsv(Path path, List<ReplayRun> runs) throws IOException {
        StringBuilder out = new StringBuilder("mode,phase,wall_ns,cpu_ns,allocated_bytes,count\n");
        for (ReplayRun run : runs) for (Map.Entry<String, PhaseMetric> entry : run.phases.entrySet()) {
            PhaseMetric metric = entry.getValue();
            out.append(run.mode).append(',').append(entry.getKey()).append(',').append(metric.wallNs).append(',')
                    .append(metric.cpuNs).append(',').append(metric.allocatedBytes).append(',').append(metric.count).append('\n');
        }
        Files.writeString(path, out);
    }

    private static String summary(JsonObject result, ReplayRun... runs) {
        StringBuilder out = new StringBuilder("# Real model class replay\n\n");
        out.append("Semantic SHA-256: `").append(result.get("semantic_sha256").getAsString()).append("`\n\n")
                .append("Stock repeated digest: **equal**. Identity candidate: **equal**. Deliberate semantic fault: **detected** at `")
                .append(result.get("fault_probe_injected_at").getAsString()).append("`.\n\n")
                .append("| mode | phase | wall ms | CPU ms | allocated bytes | count |\n|---|---|---:|---:|---:|---:|\n");
        for (ReplayRun run : runs) for (Map.Entry<String, PhaseMetric> entry : run.phases.entrySet()) {
            PhaseMetric m = entry.getValue();
            out.append('|').append(run.mode).append('|').append(entry.getKey()).append('|')
                    .append(String.format(Locale.ROOT, "%.3f", m.wallNs / 1_000_000.0)).append('|')
                    .append(m.cpuNs < 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", m.cpuNs / 1_000_000.0)).append('|')
                    .append(m.allocatedBytes).append('|').append(m.count).append("|\n");
        }
        out.append("\nThese are exclusive harness phase measurements on one headless JVM. They are not TTMM and are not summed into a startup-saving claim.\n")
                .append("The digest explicitly excludes current sprites, FaceBakery/BakedQuad vertices, render buckets, ModelState-dependent output, callbacks and GL.\n");
        return out.toString();
    }

    private static void selfTest() throws Exception {
        Map<String, Source> sources = new TreeMap<>();
        addSynthetic(sources, "bootoptim_test:base", "{\"textures\":{\"all\":\"minecraft:block/stone\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"shade\":true,\"faces\":{\"north\":{\"texture\":\"#all\",\"cullface\":\"north\",\"tintindex\":1}}}]}");
        addSynthetic(sources, "bootoptim_test:child", "{\"parent\":\"bootoptim_test:base\",\"textures\":{\"all\":\"minecraft:block/dirt\"},\"display\":{\"gui\":{\"rotation\":[30,45,0],\"scale\":[0.8,0.8,0.8]}}}");
        addSynthetic(sources, "bootoptim_test:missing", "{\"parent\":\"bootoptim_test:not_present\"}");
        addSynthetic(sources, "bootoptim_test:cycle_a", "{\"parent\":\"bootoptim_test:cycle_b\"}");
        addSynthetic(sources, "bootoptim_test:cycle_b", "{\"parent\":\"bootoptim_test:cycle_a\"}");
        List<String> roots = new ArrayList<>(sources.keySet());
        ReplayRun first = execute("stock-self-1", sources, roots);
        ReplayRun second = execute("stock-self-2", sources, roots);
        ReplayRun identity = execute("identity-self", sources, roots);
        Diff repeat = diff(first.semantic, second.semantic, "$", true);
        Diff candidate = diff(first.semantic, identity.semantic, "$", true);
        if (repeat != null) throw new AssertionError("self-test stock nondeterminism: " + repeat);
        if (candidate != null) throw new AssertionError("self-test identity mismatch: " + candidate);
        JsonObject bad = first.semantic.deepCopy();
        injectSemanticFault(bad);
        if (diff(first.semantic, bad, "$", true) == null) throw new AssertionError("self-test fault probe not detected");
        boolean sawMissing = first.events.stream().anyMatch(e -> e.kind.equals("fallback") && e.code.equals("parent_missing"));
        if (!sawMissing) throw new AssertionError("self-test did not observe missing-parent fallback lookup");
        System.out.println("ModelClassReplay self-test: real BlockModel parse/parent/material/ElementsModel paths passed; digest regression probe passed");
    }

    private static void addSynthetic(Map<String, Source> sources, String id, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        sources.put(id, new Source(id, bytes, "synthetic-controlled", id + ".json", sha256(bytes), new JsonArray()));
    }

    private static void addNullableString(JsonObject object, String key, String value) {
        if (value == null) object.add(key, JsonNull.INSTANCE); else object.addProperty(key, value);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Source(String id, byte[] bytes, String sourceId, String entry, String sha256, JsonArray shadowed) {}
    private record PhaseMetric(long wallNs, long cpuNs, long allocatedBytes, int count) {
        JsonObject toJson() { JsonObject j = new JsonObject(); j.addProperty("wall_ns", wallNs); j.addProperty("cpu_ns", cpuNs); j.addProperty("allocated_bytes", allocatedBytes); j.addProperty("count", count); return j; }
    }
    private record Diff(String path, String stock, String candidate) {
        @Override public String toString() { return path + " stock=" + stock + " candidate=" + candidate; }
    }

    private static final class ReplayRun {
        final String mode; final Map<String, BlockModel> models; final Map<String, PhaseMetric> phases; final JsonObject semantic; final List<Event> events;
        ReplayRun(String mode, Map<String, BlockModel> models, Map<String, PhaseMetric> phases, JsonObject semantic, List<Event> events) {
            this.mode = mode; this.models = models; this.phases = phases; this.semantic = semantic; this.events = events;
        }
        JsonObject toJson() {
            JsonObject json = new JsonObject(); json.addProperty("mode", mode);
            JsonObject phaseJson = new JsonObject(); phases.forEach((name, metric) -> phaseJson.add(name, metric.toJson()));
            json.add("phases", phaseJson); json.addProperty("semantic_sha256", sha256(CANONICAL_GSON.toJson(semantic).getBytes(StandardCharsets.UTF_8)));
            json.addProperty("model_count", models.size()); json.addProperty("event_count", events.size()); return json;
        }
    }

    private record Event(String kind, String code, String model, String detail, String exceptionClass) {
        static Event error(String code, String model, Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) current = invocation.getCause();
            return new Event("error", code, model, current.getMessage(), current.getClass().getName());
        }
        String sortKey() { return kind + "\0" + code + "\0" + String.valueOf(model) + "\0" + String.valueOf(detail); }
        JsonObject toSemanticJson() {
            JsonObject json = new JsonObject(); json.addProperty("kind", kind); json.addProperty("code", code);
            addNullableString(json, "model", model); addNullableString(json, "detail", detail); addNullableString(json, "exception_class", exceptionClass); return json;
        }
    }

    private static final class PhaseMeasurement {
        private final long wallStart, cpuStart, allocationStart;
        PhaseMeasurement(long wallStart, long cpuStart, long allocationStart) { this.wallStart = wallStart; this.cpuStart = cpuStart; this.allocationStart = allocationStart; }
        PhaseMetric finish(int count) {
            long allocationEnd = THREAD_METRICS.allocated(); long cpuEnd = THREAD_METRICS.cpu(); long wallEnd = System.nanoTime();
            return new PhaseMetric(wallEnd - wallStart, delta(cpuStart, cpuEnd), delta(allocationStart, allocationEnd), count);
        }
        private static long delta(long start, long end) { return start < 0 || end < 0 ? -1 : end - start; }
    }

    private static final class ThreadMetrics {
        private final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        private final Object allocationBean;
        private final Method getAllocated;
        ThreadMetrics() {
            if (bean.isCurrentThreadCpuTimeSupported() && !bean.isThreadCpuTimeEnabled()) {
                try { bean.setThreadCpuTimeEnabled(true); } catch (RuntimeException ignored) {}
            }
            Object allocBean = null; Method allocated = null;
            try {
                Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
                if (type.isInstance(bean)) {
                    Method supported = type.getMethod("isThreadAllocatedMemorySupported");
                    Method enabled = type.getMethod("isThreadAllocatedMemoryEnabled");
                    Method setter = type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                    if (Boolean.TRUE.equals(supported.invoke(bean))) {
                        if (!Boolean.TRUE.equals(enabled.invoke(bean))) setter.invoke(bean, true);
                        allocated = type.getMethod("getThreadAllocatedBytes", long.class); allocBean = bean;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
            allocationBean = allocBean; getAllocated = allocated;
        }
        PhaseMeasurement start() { return new PhaseMeasurement(System.nanoTime(), cpu(), allocated()); }
        long cpu() { return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getCurrentThreadCpuTime() : -1; }
        long allocated() {
            if (allocationBean == null || getAllocated == null) return -1;
            try { return ((Number) getAllocated.invoke(allocationBean, Thread.currentThread().threadId())).longValue(); }
            catch (ReflectiveOperationException | RuntimeException ignored) { return -1; }
        }
    }
}
