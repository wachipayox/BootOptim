package dev.wachipayox.bootoptim.profiling.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Profile-only accounting for disjoint synchronous work inside the 1.21.1 ModelBakery constructor. */
public final class ModelBakeryPrepareAttribution {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelBakeryPrepareAttribution");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileModelBakeryAttribution");
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private ModelBakeryPrepareAttribution() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void beginConstructor() {
        if (ENABLED) STATE.set(new State(System.nanoTime()));
    }

    public static long start() {
        return ENABLED && STATE.get() != null ? System.nanoTime() : 0L;
    }

    public static void recordBlockStates(long startedNanos) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) state.blockStatesNanos += elapsed(startedNanos);
    }

    public static void recordParentResolution(long startedNanos, int count) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) {
            state.parentNanos += elapsed(startedNanos);
            state.parentModels += count;
        }
    }

    public static void recordItem(ResourceLocation location, long startedNanos) {
        State state = STATE.get();
        if (state == null || startedNanos == 0L) return;
        long elapsed = elapsed(startedNanos);
        state.itemNanos += elapsed;
        state.itemModels++;
        state.itemNamespaces.computeIfAbsent(location.getNamespace(), ignored -> new Stats()).add(elapsed);
    }

    public static void recordMissingModel(long startedNanos) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) state.missingModelNanos += elapsed(startedNanos);
    }

    public static void recordModelGroups(long startedNanos) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) state.modelGroupsNanos += elapsed(startedNanos);
    }

    public static void recordSpecialModel(long startedNanos) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) {
            state.specialNanos += elapsed(startedNanos);
            state.specialModels++;
        }
    }

    public static void recordNeoForgeAdditionalEvent(long startedNanos, int count) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) {
            state.neoForgeEventNanos += elapsed(startedNanos);
            state.neoForgeAdditionalModels = count;
        }
    }

    public static void recordNeoForgeAdditionalGet(ResourceLocation location, long startedNanos) {
        recordNamespace(location, startedNanos, true, false);
    }

    public static void recordNeoForgeAdditionalRegister(ResourceLocation location, long startedNanos) {
        recordNamespace(location, startedNanos, false, false);
    }

    public static void recordFabricPluginInit(long startedNanos) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) state.fabricPluginInitNanos += elapsed(startedNanos);
    }

    /** Records one non-overlapping Consumer.accept call from FFAPI's addExtraModels loop. */
    public static void recordFabricExtraModel(ResourceLocation location, long startedNanos) {
        recordNamespace(location, startedNanos, false, true);
    }

    private static void recordNamespace(ResourceLocation location, long startedNanos, boolean neoGet, boolean fabric) {
        State state = STATE.get();
        if (state == null || startedNanos == 0L) return;
        long elapsed = elapsed(startedNanos);
        if (fabric) {
            state.fabricExtraNanos += elapsed;
            state.fabricExtraModels++;
            state.fabricExtraNamespaces.computeIfAbsent(location.getNamespace(), ignored -> new Stats()).add(elapsed);
        } else if (neoGet) {
            state.neoForgeGetNanos += elapsed;
            state.neoForgeNamespaces.computeIfAbsent(location.getNamespace(), ignored -> new Stats()).add(elapsed);
        } else {
            state.neoForgeRegisterNanos += elapsed;
        }
    }

    public static void finishConstructor() {
        State state = STATE.get();
        STATE.remove();
        if (state == null) return;
        long total = System.nanoTime() - state.constructorStartedNanos;
        long originalKnown = state.blockStatesNanos + state.itemNanos + state.parentNanos;
        long residualBeforeSplit = Math.max(0L, total - originalKnown);
        long newlyKnown = state.missingModelNanos + state.modelGroupsNanos + state.specialNanos
                + state.neoForgeEventNanos + state.neoForgeGetNanos + state.neoForgeRegisterNanos
                + state.fabricPluginInitNanos + state.fabricExtraNanos;
        long residualAfterSplit = Math.max(0L, residualBeforeSplit - newlyKnown);
        LOGGER.info(
                "BOOTOPTIM_MODEL_BAKERY_PREPARE kind=summary constructor_ms={} blockstate_registration_ms={} item_dependencies_ms={} item_models={} parent_resolution_ms={} parent_models={} residual_before_split_ms={} missing_model_ms={} model_groups_ms={} special_models_ms={} special_models={} fabric_plugin_init_ms={} fabric_extra_models_ms={} fabric_extra_models={} neoforge_additional_event_ms={} neoforge_additional_models={} neoforge_additional_get_ms={} neoforge_additional_register_ms={} residual_after_split_ms={}",
                ms(total), ms(state.blockStatesNanos), ms(state.itemNanos), state.itemModels,
                ms(state.parentNanos), state.parentModels, ms(residualBeforeSplit),
                ms(state.missingModelNanos), ms(state.modelGroupsNanos), ms(state.specialNanos), state.specialModels,
                ms(state.fabricPluginInitNanos), ms(state.fabricExtraNanos), state.fabricExtraModels,
                ms(state.neoForgeEventNanos), state.neoForgeAdditionalModels, ms(state.neoForgeGetNanos),
                ms(state.neoForgeRegisterNanos), ms(residualAfterSplit));
        logNamespaces("item_namespace", state.itemNamespaces, state.itemNanos);
        logNamespaces("fabric_extra_namespace", state.fabricExtraNamespaces, state.fabricExtraNanos);
        logNamespaces("neoforge_additional_namespace", state.neoForgeNamespaces, state.neoForgeGetNanos);
    }

    private static void logNamespaces(String kind, Map<String, Stats> namespaces, long total) {
        List<Map.Entry<String, Stats>> rows = new ArrayList<>(namespaces.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Stats>>comparingLong(entry -> entry.getValue().nanos).reversed());
        int rank = 0;
        for (Map.Entry<String, Stats> entry : rows) {
            if (++rank > 20) break;
            LOGGER.info(
                    "BOOTOPTIM_MODEL_BAKERY_PREPARE kind={} rank={} namespace={} calls={} wall_ms={} share_percent={}",
                    kind, rank, entry.getKey(), entry.getValue().calls, ms(entry.getValue().nanos),
                    percent(entry.getValue().nanos, total));
        }
    }

    private static long elapsed(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0L ? 0.0D : part * 100.0D / total);
    }

    private static final class State {
        final long constructorStartedNanos;
        final Map<String, Stats> itemNamespaces = new HashMap<>();
        final Map<String, Stats> fabricExtraNamespaces = new HashMap<>();
        final Map<String, Stats> neoForgeNamespaces = new HashMap<>();
        long blockStatesNanos;
        long itemNanos;
        long parentNanos;
        long missingModelNanos;
        long modelGroupsNanos;
        long specialNanos;
        long fabricPluginInitNanos;
        long fabricExtraNanos;
        long neoForgeEventNanos;
        long neoForgeGetNanos;
        long neoForgeRegisterNanos;
        long itemModels;
        long parentModels;
        long specialModels;
        long fabricExtraModels;
        int neoForgeAdditionalModels;

        State(long constructorStartedNanos) {
            this.constructorStartedNanos = constructorStartedNanos;
        }
    }

    private static final class Stats {
        long calls;
        long nanos;

        void add(long elapsed) {
            calls++;
            nanos += elapsed;
        }
    }
}
