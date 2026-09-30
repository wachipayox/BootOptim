package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler.Phase;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.block.model.BlockModelDefinition;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact 1.21.1 callsites, including all four bytecode copies of the finally block. */
@Mixin(BlockStateModelLoader.class)
abstract class BlockStateWorkAttributionMixin {
    @WrapOperation(method = "lambda$loadBlockStateDefinitions$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockModelShaper;stateToModelLocation(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/resources/model/ModelResourceLocation;"), require = 0)
    private static ModelResourceLocation bootoptim$location(ResourceLocation id, BlockState state,
            Operation<ModelResourceLocation> original) {
        return ModelGroupingProfiler.phase(Phase.LOCATION, () -> original.call(id, state));
    }

    @WrapOperation(method = "loadBlockStateDefinitions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedJson;parse(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/block/model/BlockModelDefinition$Context;)Lnet/minecraft/client/renderer/block/model/BlockModelDefinition;"), require = 0)
    private BlockModelDefinition bootoptim$parse(BlockStateModelLoader.LoadedJson json,
            ResourceLocation id, BlockModelDefinition.Context context, Operation<BlockModelDefinition> original) {
        return ModelGroupingProfiler.phase(Phase.PARSE, () -> original.call(json, id, context));
    }

    @WrapOperation(method = "lambda$loadBlockStateDefinitions$10", at = @At(value = "INVOKE",
            target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"), require = 0)
    private void bootoptim$discovery(BiConsumer<?, ?> consumer, Object id, Object model, Operation<Void> original) {
        ModelGroupingProfiler.phase(Phase.DISCOVERY, () -> original.call(consumer, id, model));
    }

    @WrapOperation(method = "loadBlockStateDefinitions", at = {
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 1),
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 3),
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 5),
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 7)
    }, require = 0)
    private void bootoptim$publication(Map<?, ?> map, BiConsumer<?, ?> consumer, Operation<Void> original) {
        ModelGroupingProfiler.phase(Phase.PUBLICATION, () -> original.call(map, consumer));
    }

    @WrapOperation(method = "loadBlockStateDefinitions", at = {
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 2),
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 4),
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 6),
            @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", ordinal = 8)
    }, require = 0)
    private void bootoptim$finalization(Map<?, ?> map, BiConsumer<?, ?> consumer, Operation<Void> original) {
        ModelGroupingProfiler.phase(Phase.FINALIZATION, () -> original.call(map, consumer));
    }
}
