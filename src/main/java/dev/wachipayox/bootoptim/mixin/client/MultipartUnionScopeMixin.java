package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.ValidatedDependencyUnion;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ModelBakery.class)
abstract class MultipartUnionScopeMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadAllBlockStates()V"), require = 0)
    private void bootoptim$unionScope(BlockStateModelLoader loader, Operation<Void> original) {
        ValidatedDependencyUnion.loadAll(() -> original.call(loader));
    }
}
