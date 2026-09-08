package dev.wachipayox.bootoptim.replay.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Tooling-only FML lifecycle bridge. This class is added to the main dev source set only by
 * model-replay.init.gradle; it is never part of the normal BootOptim build or packaged JAR.
 */
@EventBusSubscriber(modid = "boot_optim", bus = EventBusSubscriber.Bus.MOD)
public final class ModelClassReplayFmlProbe {
    private static final AtomicBoolean RAN = new AtomicBoolean();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ModelClassReplayFmlProbe() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        String manifestValue = System.getenv("BOOTOPTIM_MODEL_REPLAY_MANIFEST");
        String outputValue = System.getenv("BOOTOPTIM_MODEL_REPLAY_OUTPUT");
        if (manifestValue == null || outputValue == null || !RAN.compareAndSet(false, true)) {
            return;
        }

        Path manifest = Path.of(manifestValue).toAbsolutePath();
        Path output = Path.of(outputValue).toAbsolutePath();
        try {
            Method runManifest = ModelClassReplay.class.getDeclaredMethod("runManifest", Path.class, Path.class);
            runManifest.setAccessible(true);
            try {
                runManifest.invoke(null, manifest, output);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new RuntimeException(cause == null ? exception : cause);
            }

            Path resultPath = output.resolve("replay.json");
            if (!Files.isRegularFile(resultPath)) {
                throw new IllegalStateException("model replay did not produce replay.json");
            }
            JsonObject result = JsonParser.parseString(Files.readString(resultPath)).getAsJsonObject();
            JsonObject phases = result.getAsJsonObject("stock_1").getAsJsonObject("phases");
            requirePositive(phases, "real_blockmodel_parse");
            requirePositive(phases, "real_parent_resolution");
            requirePositive(phases, "real_texture_chain_material_lookup");
            requirePositive(phases, "real_elementsmodel_construction");
            if (!result.get("stock_repeat_equal").getAsBoolean()
                    || !result.get("identity_candidate_equal").getAsBoolean()
                    || !result.get("fault_probe_detected").getAsBoolean()) {
                throw new IllegalStateException("semantic replay gates did not all pass");
            }

            JsonObject controlled = controlledFallbackAndErrorProbe();
            Files.writeString(output.resolve("controlled-fallback-error.json"), PRETTY.toJson(controlled) + "\n");
            if (!controlled.get("missing_parent_resolver_called").getAsBoolean()
                    || !controlled.get("cycle_observed").getAsBoolean()
                    || controlled.get("invalid_json_exception_class").isJsonNull()) {
                throw new IllegalStateException("controlled fallback/error probes did not all pass: " + controlled);
            }

            System.out.println("MODEL_CLASS_FML_REPLAY_OK semantic_sha256="
                    + result.get("semantic_sha256").getAsString()
                    + " controlled_missing_parent=true controlled_cycle=true controlled_invalid_json=true");
        } catch (Exception exception) {
            throw new RuntimeException("FML model replay probe failed", exception);
        }
    }

    private static JsonObject controlledFallbackAndErrorProbe() throws Exception {
        JsonObject out = new JsonObject();

        BlockModel missing = parse("{\"parent\":\"bootoptim_test:not_present\"}");
        List<String> missingRequests = new ArrayList<>();
        resolve(missing, location -> {
            missingRequests.add(location.toString());
            return null;
        });
        JsonArray requested = new JsonArray();
        missingRequests.forEach(requested::add);
        out.add("missing_parent_requests", requested);
        out.addProperty("missing_parent_resolver_called", missingRequests.contains("bootoptim_test:not_present"));
        out.addProperty("missing_parent_probe_class", missing.getClass().getName());

        Map<String, BlockModel> cycle = new LinkedHashMap<>();
        cycle.put("bootoptim_test:cycle_a", parse("{\"parent\":\"bootoptim_test:cycle_b\"}"));
        cycle.put("bootoptim_test:cycle_b", parse("{\"parent\":\"bootoptim_test:cycle_a\"}"));
        resolve(cycle.get("bootoptim_test:cycle_a"), location -> cycle.get(location.toString()));
        resolve(cycle.get("bootoptim_test:cycle_b"), location -> cycle.get(location.toString()));
        out.addProperty("cycle_observed", hasParentCycle(cycle.get("bootoptim_test:cycle_a"))
                || hasParentCycle(cycle.get("bootoptim_test:cycle_b")));
        out.addProperty("cycle_downstream_lowering_attempted", false);

        String invalidException = null;
        try {
            parse("{\"elements\":[");
        } catch (Throwable throwable) {
            invalidException = unwrap(throwable).getClass().getName();
        }
        if (invalidException == null) out.add("invalid_json_exception_class", com.google.gson.JsonNull.INSTANCE);
        else out.addProperty("invalid_json_exception_class", invalidException);
        return out;
    }

    private static BlockModel parse(String json) throws Throwable {
        Method method = Arrays.stream(BlockModel.class.getMethods())
                .filter(candidate -> java.lang.reflect.Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> candidate.getName().equals("fromStream"))
                .filter(candidate -> candidate.getParameterCount() == 1
                        && Reader.class.isAssignableFrom(candidate.getParameterTypes()[0]))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BlockModel.fromStream(Reader)"));
        try (Reader reader = new StringReader(json)) {
            try {
                return (BlockModel) method.invoke(null, reader);
            } catch (InvocationTargetException exception) {
                throw exception.getCause() == null ? exception : exception.getCause();
            }
        }
    }

    private static void resolve(BlockModel model, Function<ResourceLocation, UnbakedModel> resolver) throws Throwable {
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

    private static boolean hasParentCycle(BlockModel start) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Object current = start;
        for (int depth = 0; depth < 128 && current != null; depth++) {
            if (seen.put(current, Boolean.TRUE) != null) return true;
            current = readField(current, "parent");
        }
        return false;
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private static void requirePositive(JsonObject phases, String phase) {
        JsonObject metric = phases.getAsJsonObject(phase);
        if (metric == null || metric.get("count").getAsLong() <= 0L) {
            throw new IllegalStateException("phase did not execute real operations: " + phase);
        }
    }
}
