package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bounded pass at the first fully loaded title screen (no overlay, no world) over Decocraft's
 * persistent model cache ({@code ModuleBlocks.MAT_TO_BB_MODEL}): equal embedded {@code data:image}
 * source Strings of the models' textures are pointed at a single instance
 * ({@link ExactSourceStrings}). Text, models, vectors and images are not shared, freed or changed.
 *
 * <p>Runs outside resource reload and rendering. Only public field shapes are used; any class,
 * field, size or count that differs from the audited shape leaves every reference untouched and
 * logs {@code [WBOMBO-COMPAT] FALLBACK}. Kill switch: {@code -Dboot_optim.decocraftSourceStrings=false}.
 * A restart undoes a completed pass; the duplicate String objects already replaced are only
 * reclaimable by the garbage collector, which this pass does not request.</p>
 */
public final class DecocraftSourceStrings {
    private static final String MECHANISM = "decocraft-source-strings";
    private static final String MOD = "decocraft";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftSourceStrings");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("boot_optim.decocraftSourceStrings", "true"));
    private static final String CACHE_CLASS = "com.razz.decocraft.common.ModuleBlocks";
    private static final String MODEL_CLASS = "com.razz.decocraft.models.bbmodel.BBModel";
    private static final int MAX_MODELS = 10_000;
    private static final int MAX_TEXTURES_PER_MODEL = 4096;
    private static final int MAX_TEXTURES = 20_000;

    private static Consumer<ClientTickEvent.Post> listener;
    private static int PROBE_TICKS;

    private DecocraftSourceStrings() {}

    /** Called from the client-setup event: registers a tick listener that removes itself after one attempt. */
    public static void install() {
        if (!ENABLED || !ModList.get().isLoaded(MOD)) {
            return;
        }
        listener = DecocraftSourceStrings::tick;
        NeoForge.EVENT_BUS.addListener(listener);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isGameLoadFinished() || client.getOverlay() != null || client.level != null
                || !(client.screen instanceof TitleScreen)) {
            if (PROBE_TICKS++ % 100 == 0) { // TEMPORARY probe, removed before merge
                LOGGER.info("PROBE tick={} loadFinished={} overlay={} level={} screen={}", PROBE_TICKS, client.isGameLoadFinished(),
                        client.getOverlay() == null ? null : client.getOverlay().getClass().getName(), client.level != null,
                        client.screen == null ? null : client.screen.getClass().getName());
            }
            return;
        }
        NeoForge.EVENT_BUS.unregister(listener);
        listener = null;
        long started = System.nanoTime();
        try {
            Class<?> type = Class.forName(CACHE_CLASS, false, DecocraftSourceStrings.class.getClassLoader());
            var cacheField = type.getField("MAT_TO_BB_MODEL");
            if (!Modifier.isStatic(cacheField.getModifiers()) || !Map.class.isAssignableFrom(cacheField.getType())) {
                throw new IllegalArgumentException("model_cache_shape");
            }
            if (!(cacheField.get(null) instanceof Map<?, ?> cache)) {
                throw new IllegalArgumentException("model_cache_value");
            }
            List<?> models;
            synchronized (cache) {
                if (cache.size() > MAX_MODELS) {
                    throw new IllegalArgumentException("model_count_limit");
                }
                models = new ArrayList<>(cache.values());
            }
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            List<Object> textures = new ArrayList<>();
            for (Object model : models) {
                if (model == null || seen.put(model, Boolean.TRUE) != null) {
                    continue;
                }
                if (!model.getClass().getName().equals(MODEL_CLASS)) {
                    throw new IllegalArgumentException("model_type");
                }
                var field = model.getClass().getField("textures");
                if (!List.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException("textures_shape");
                }
                Object entries = field.get(model);
                if (entries == null) {
                    continue;
                }
                if (!(entries instanceof List<?> list) || list.size() > MAX_TEXTURES_PER_MODEL
                        || textures.size() + list.size() > MAX_TEXTURES) {
                    throw new IllegalArgumentException("textures_value_or_limit");
                }
                textures.addAll(list);
            }
            ExactSourceStrings.Result result = ExactSourceStrings.share(textures);
            if (result.fields() == 0) {
                throw new IllegalArgumentException("no_embedded_sources");
            }
            CompatLog.active(MECHANISM, MOD);
            LOGGER.info("BOOTOPTIM_DECOCRAFT_SOURCE_STRINGS source_fields={} replaced_references={} unique_characters_before={} unique_characters_after={} duration_ns={}",
                    result.fields(), result.replaced(), result.uniqueCharactersBefore(), result.uniqueCharactersAfter(),
                    System.nanoTime() - started);
        } catch (Exception | LinkageError unavailable) {
            CompatLog.fallback(MECHANISM, MOD, "data_contract_unavailable_" + unavailable.getClass().getSimpleName());
        }
    }
}
