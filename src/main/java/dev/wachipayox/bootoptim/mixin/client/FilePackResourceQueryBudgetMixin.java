package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.ResourceQueryBudget;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FilePackResources.class)
abstract class FilePackResourceQueryBudgetMixin {
    @WrapMethod(method = "listResources")
    private void bootoptim$query(PackType type, String namespace, String path,
            PackResources.ResourceOutput output, Operation<Void> original) {
        if (!ResourceQueryBudget.ENABLED) { original.call(type, namespace, path, output); return; }
        var frame = ResourceQueryBudget.begin((PackResources) this, type, namespace, path);
        boolean success = false;
        try { original.call(type, namespace, path, output); success = true; }
        finally { ResourceQueryBudget.finish(frame, success); }
    }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/PackResources$ResourceOutput;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void bootoptim$output(PackResources.ResourceOutput output, Object location,
            Object supplier, Operation<Void> original) {
        if (!ResourceQueryBudget.ENABLED) { original.call(output, location, supplier); return; }
        ResourceQueryBudget.callback(() -> original.call(output, location, supplier));
    }
}
