package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.ValidatedDependencyUnion;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Stream;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiPart.class)
abstract class MultiPartValidatedUnionMixin {
    @WrapOperation(method = "getDependencies", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Collectors;toSet()Ljava/util/stream/Collector;"), require = 0)
    private Collector<ResourceLocation, ?, Set<ResourceLocation>> bootoptim$validatedUnion(
            Operation<Collector<ResourceLocation, ?, Set<ResourceLocation>>> original) {
        var stock = original.call();
        return ((Object) this).getClass() == MultiPart.class
                ? ValidatedDependencyUnion.collector(this, ResourceLocation.class, stock) : stock;
    }

    @WrapOperation(method = "getDependencies", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"), require = 0)
    private Object bootoptim$preserveParallel(Stream<ResourceLocation> stream,
            Collector<ResourceLocation, ?, Set<ResourceLocation>> collector, Operation<Object> original) {
        return original.call(stream, ValidatedDependencyUnion.forStream(collector, stream.isParallel()));
    }
}
