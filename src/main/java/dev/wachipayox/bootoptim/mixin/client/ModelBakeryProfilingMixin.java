package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadProfiler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fine-grained timings inside ModelManager's asynchronous preparation path. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryProfilingMixin {
    private static final Logger BOOTOPTIM$LOGGER = LoggerFactory.getLogger("BootOptim/ModelBakeProfiler");

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", ordinal = 0, shift = At.Shift.AFTER))
    private void bootoptim$modelBakeryStart(BlockColors blockColors, ProfilerFiller profiler, Map<ResourceLocation, BlockModel> models, Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStates, CallbackInfo ci) {
        ModelReloadProfiler.begin("model_bakery_construct");
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$modelBakeryEnd(BlockColors blockColors, ProfilerFiller profiler, Map<ResourceLocation, BlockModel> models, Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStates, CallbackInfo ci) {
        ModelReloadProfiler.end("model_bakery_construct", null);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$modelBakeStart(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        ModelReloadProfiler.begin("model_bake");
    }

    @Redirect(
            method = "bakeModels",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void bootoptim$profileTopLevelBakeDistribution(
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
            boolean exactVanillaBlockstate = bootoptim$isExactVanillaBlockstateModel(model);
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
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_MODEL_BAKE_DISTRIBUTION total_calls={} elapsed_ms={} identity_first_calls={} identity_first_ms={} identity_repeat_calls={} identity_repeat_ms={} identity_repeat_time_percent={} safe_first_calls={} safe_first_ms={} safe_repeat_calls={} safe_repeat_ms={} safe_repeat_time_percent={}",
                models.size(),
                bootoptim$ms(elapsedNanos),
                identityFirst.calls,
                bootoptim$ms(identityFirst.totalNanos),
                identityRepeat.calls,
                bootoptim$ms(identityRepeat.totalNanos),
                bootoptim$percent(identityRepeat.totalNanos, identityFirst.totalNanos + identityRepeat.totalNanos),
                safeFirst.calls,
                bootoptim$ms(safeFirst.totalNanos),
                safeRepeat.calls,
                bootoptim$ms(safeRepeat.totalNanos),
                bootoptim$percent(safeRepeat.totalNanos, identityFirst.totalNanos + identityRepeat.totalNanos));

        bootoptim$logTop("class", byClass, 15);
        bootoptim$logTop("namespace", byNamespace, 20);
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$modelBakeEnd(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        ModelReloadProfiler.end("model_bake", null);
    }

    private static boolean bootoptim$isExactVanillaBlockstateModel(UnbakedModel model) {
        Class<?> type = model.getClass();
        return type == MultiVariant.class || type == MultiPart.class;
    }

    private static void bootoptim$logTop(String dimension, Map<?, BakeStats> stats, int limit) {
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
            BOOTOPTIM$LOGGER.info(
                    "BOOTOPTIM_MODEL_BAKE_TOP dimension={} rank={} key={} calls={} total_ms={} share_percent={} avg_us={} max_us={} max_location={}",
                    dimension,
                    rank,
                    key,
                    value.calls,
                    bootoptim$ms(value.totalNanos),
                    bootoptim$percent(value.totalNanos, allNanos),
                    String.format(Locale.ROOT, "%.3f", value.calls == 0 ? 0.0 : value.totalNanos / 1_000.0 / value.calls),
                    String.format(Locale.ROOT, "%.3f", value.maxNanos / 1_000.0),
                    value.maxLocation);
        }
    }

    private static String bootoptim$ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String bootoptim$percent(long part, long total) {
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

        void add(long nanos) {
            add(nanos, "-");
        }
    }
}
