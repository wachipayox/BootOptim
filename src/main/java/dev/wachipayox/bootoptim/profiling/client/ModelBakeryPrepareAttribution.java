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
        if (state != null && startedNanos != 0L) state.blockStatesNanos += System.nanoTime() - startedNanos;
    }

    public static void recordParentResolution(long startedNanos, int count) {
        State state = STATE.get();
        if (state != null && startedNanos != 0L) {
            state.parentNanos += System.nanoTime() - startedNanos;
            state.parentModels += count;
        }
    }

    public static void recordItem(ResourceLocation location, long startedNanos) {
        State state = STATE.get();
        if (state == null || startedNanos == 0L) return;
        long elapsed = System.nanoTime() - startedNanos;
        state.itemNanos += elapsed;
        state.itemModels++;
        state.itemNamespaces.computeIfAbsent(location.getNamespace(), ignored -> new Stats()).add(elapsed);
    }

    public static void finishConstructor() {
        State state = STATE.get();
        STATE.remove();
        if (state == null) return;
        long total = System.nanoTime() - state.constructorStartedNanos;
        long known = state.blockStatesNanos + state.itemNanos + state.parentNanos;
        long residual = Math.max(0L, total - known);
        LOGGER.info(
                "BOOTOPTIM_MODEL_BAKERY_PREPARE kind=summary constructor_ms={} blockstate_registration_ms={} item_dependencies_ms={} item_models={} parent_resolution_ms={} parent_models={} residual_ms={}",
                ms(total), ms(state.blockStatesNanos), ms(state.itemNanos), state.itemModels,
                ms(state.parentNanos), state.parentModels, ms(residual));
        List<Map.Entry<String, Stats>> rows = new ArrayList<>(state.itemNamespaces.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Stats>>comparingLong(entry -> entry.getValue().nanos).reversed());
        int rank = 0;
        for (Map.Entry<String, Stats> entry : rows) {
            if (++rank > 20) break;
            LOGGER.info(
                    "BOOTOPTIM_MODEL_BAKERY_PREPARE kind=item_namespace rank={} namespace={} calls={} wall_ms={} share_percent={}",
                    rank, entry.getKey(), entry.getValue().calls, ms(entry.getValue().nanos),
                    percent(entry.getValue().nanos, state.itemNanos));
        }
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
        long blockStatesNanos;
        long itemNanos;
        long parentNanos;
        long itemModels;
        long parentModels;

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
