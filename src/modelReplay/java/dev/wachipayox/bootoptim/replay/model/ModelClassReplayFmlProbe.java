package dev.wachipayox.bootoptim.replay.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
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
            System.out.println("MODEL_CLASS_FML_REPLAY_OK semantic_sha256="
                    + result.get("semantic_sha256").getAsString());
        } catch (Exception exception) {
            throw new RuntimeException("FML model replay probe failed", exception);
        }
    }

    private static void requirePositive(JsonObject phases, String phase) {
        JsonObject metric = phases.getAsJsonObject(phase);
        if (metric == null || metric.get("count").getAsLong() <= 0L) {
            throw new IllegalStateException("phase did not execute real operations: " + phase);
        }
    }
}
