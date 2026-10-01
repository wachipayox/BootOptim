package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler;
import java.util.function.Supplier;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockStateModelLoader.class)
abstract class ModelGroupingSupplierMixin {
    @WrapOperation(method = "lambda$loadBlockStateDefinitions$10", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"), require = 0)
    private Object bootoptim$measureCurrentGrouping(Supplier<?> supplier, Operation<Object> original) {
        return ModelGroupingProfiler.group(() -> original.call(supplier));
    }
}
