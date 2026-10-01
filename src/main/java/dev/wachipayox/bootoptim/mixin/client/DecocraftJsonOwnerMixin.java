package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import dev.wachipayox.bootoptim.profiling.client.DecocraftJsonMetrics;
import dev.wachipayox.bootoptim.optimization.client.DecocraftModelArchiveBatch;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;

/** Times full read/parse/close tasks, including candidate guard and lazy corpus fill. */
@Mixin(ModelManager.class)
abstract class DecocraftJsonOwnerMixin {
    @WrapMethod(method = "loadBlockModels", require = 0)
    private static CompletableFuture<Map<ResourceLocation, BlockModel>> bootoptim$modelsFuture(
            ResourceManager manager, Executor executor,
            Operation<CompletableFuture<Map<ResourceLocation, BlockModel>>> original) {
        if (!DecocraftJsonMetrics.ENABLED) return original.call(manager, executor);
        long begin = System.nanoTime(); int generation = DecocraftModelArchiveBatch.generation();
        var result = original.call(manager, executor);
        result.whenComplete((ignored, failure) -> LogUtils.getLogger().info(
                "BOOTOPTIM_JSON_PREREQUISITE generation={} phase=models success={} wall_ns={}",
                generation, failure == null, System.nanoTime()-begin));
        return result;
    }
    @WrapMethod(method = "loadBlockStates", require = 0)
    private static CompletableFuture<Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>>> bootoptim$statesFuture(
            ResourceManager manager, Executor executor,
            Operation<CompletableFuture<Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>>>> original) {
        if (!DecocraftJsonMetrics.ENABLED) return original.call(manager, executor);
        long begin = System.nanoTime(); int generation = DecocraftModelArchiveBatch.generation();
        var result = original.call(manager, executor);
        result.whenComplete((ignored, failure) -> LogUtils.getLogger().info(
                "BOOTOPTIM_JSON_PREREQUISITE generation={} phase=states success={} wall_ns={}",
                generation, failure == null, System.nanoTime()-begin));
        return result;
    }
    @WrapMethod(method = "lambda$loadBlockModels$8", remap = false, require = 0)
    private static Pair<ResourceLocation, BlockModel> bootoptim$modelTask(Map.Entry<ResourceLocation, Resource> entry,
            Operation<Pair<ResourceLocation, BlockModel>> original) {
        if (!DecocraftJsonMetrics.eligible(entry.getKey(), entry.getValue())) return original.call(entry);
        var stamp = DecocraftJsonMetrics.start();
        try { return original.call(entry); } finally { DecocraftJsonMetrics.taskEnd(stamp); }
    }
    @WrapMethod(method = "lambda$loadBlockStates$12", remap = false, require = 0)
    private static Pair<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> bootoptim$stateTask(
            Map.Entry<ResourceLocation, List<Resource>> entry,
            Operation<Pair<ResourceLocation, List<BlockStateModelLoader.LoadedJson>>> original) {
        if (entry.getValue().isEmpty() || !entry.getValue().stream().allMatch(resource ->
                DecocraftJsonMetrics.eligible(entry.getKey(), resource))) return original.call(entry);
        var stamp = DecocraftJsonMetrics.start();
        try { return original.call(entry); } finally { DecocraftJsonMetrics.taskEnd(stamp); }
    }
}
