package dev.wachipayox.bootoptim.profiling.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Diagnostic-only distribution profiler for eager top-level model baking. */
public final class ModelBakeDistributionProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelBakeProfiler");

    private ModelBakeDistributionProfiler() {
    }

    public static void profile(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> bakeAction) {
        IdentityHashMap<UnbakedModel, Boolean> identities = new IdentityHashMap<>();
        Map<Class<?>, BakeStats> byClass = new HashMap<>();
        Map<String, BakeStats> byNamespace = new HashMap<>();
        BakeStats identityFirst = new BakeStats();
        BakeStats identityRepeat = new BakeStats();
        BakeStats safeFirst = new BakeStats();
        BakeStats safeRepeat = new BakeStats();
        long startedNanos = System.nanoTime();

        models.forEach((location, model) -> {
            boolean repeatedIdentity = identities.put(model, Boolean.TRUE) != null;
            boolean exactVanillaBlockstate = isExactVanillaBlockstateModel(model);
            long callStartedNanos = System.nanoTime();
            bakeAction.accept(location, model);
            long elapsedNanos = System.nanoTime() - callStartedNanos;

            String locationText = location.toString();
            byClass.computeIfAbsent(model.getClass(), ignored -> new BakeStats()).add(elapsedNanos, locationText);
            byNamespace.computeIfAbsent(location.id().getNamespace(), ignored -> new BakeStats()).add(elapsedNanos, locationText);
            (repeatedIdentity ? identityRepeat : identityFirst).add(elapsedNanos, locationText);
            if (exactVanillaBlockstate) {
                (repeatedIdentity ? safeRepeat : safeFirst).add(elapsedNanos, locationText);
            }
        });

        long elapsedNanos = System.nanoTime() - startedNanos;
        long timedNanos = identityFirst.totalNanos + identityRepeat.totalNanos;
        LOGGER.info(
                "BOOTOPTIM_MODEL_BAKE_DISTRIBUTION total_calls={} elapsed_ms={} timed_calls_ms={} profiler_overhead_ms={} identity_first_calls={} identity_first_ms={} identity_repeat_calls={} identity_repeat_ms={} identity_repeat_time_percent={} safe_first_calls={} safe_first_ms={} safe_repeat_calls={} safe_repeat_ms={} safe_repeat_time_percent={}",
                models.size(),
                ms(elapsedNanos),
                ms(timedNanos),
                ms(Math.max(0L, elapsedNanos - timedNanos)),
                identityFirst.calls,
                ms(identityFirst.totalNanos),
                identityRepeat.calls,
                ms(identityRepeat.totalNanos),
                percent(identityRepeat.totalNanos, timedNanos),
                safeFirst.calls,
                ms(safeFirst.totalNanos),
                safeRepeat.calls,
                ms(safeRepeat.totalNanos),
                percent(safeRepeat.totalNanos, timedNanos));

        logTop("class", byClass, 15);
        logTop("namespace", byNamespace, 20);
    }

    private static boolean isExactVanillaBlockstateModel(UnbakedModel model) {
        Class<?> type = model.getClass();
        return type == MultiVariant.class || type == MultiPart.class;
    }

    private static void logTop(String dimension, Map<?, BakeStats> stats, int limit) {
        List<Map.Entry<?, BakeStats>> sorted = new ArrayList<>(stats.entrySet());
        sorted.sort(Comparator.<Map.Entry<?, BakeStats>>comparingLong(entry -> entry.getValue().totalNanos).reversed());
        long allNanos = sorted.stream().mapToLong(entry -> entry.getValue().totalNanos).sum();
        int rank = 0;
        for (Map.Entry<?, BakeStats> entry : sorted) {
            if (++rank > limit) {
                break;
            }
            BakeStats value = entry.getValue();
            String key = entry.getKey() instanceof Class<?> type ? type.getName() : String.valueOf(entry.getKey());
            LOGGER.info(
                    "BOOTOPTIM_MODEL_BAKE_TOP dimension={} rank={} key={} calls={} total_ms={} share_percent={} avg_us={} max_us={} max_location={}",
                    dimension,
                    rank,
                    key,
                    value.calls,
                    ms(value.totalNanos),
                    percent(value.totalNanos, allNanos),
                    String.format(Locale.ROOT, "%.3f", value.calls == 0 ? 0.0 : value.totalNanos / 1_000.0 / value.calls),
                    String.format(Locale.ROOT, "%.3f", value.maxNanos / 1_000.0),
                    value.maxLocation);
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.2f", total == 0 ? 0.0 : part * 100.0 / total);
    }

    private static final class BakeStats {
        int calls;
        long totalNanos;
        long maxNanos;
        String maxLocation = "-";

        void add(long nanos, String location) {
            calls++;
            totalNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
                maxLocation = location;
            }
        }
    }
}
