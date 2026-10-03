package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.ResourceResolutionBudget;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FallbackResourceManager.class)
abstract class FallbackResourceResolutionBudgetMixin {
    @WrapMethod(method = "getResource(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;")
    private Optional<Resource> bootoptim$point(ResourceLocation id, Operation<Optional<Resource>> original) {
        var scope = ResourceResolutionBudget.begin(this, id, "point");
        boolean success = false;
        int outputs = 0;
        try {
            Optional<Resource> result = original.call(id);
            outputs = result != null && result.isPresent() ? 1 : 0;
            success = true;
            return result;
        } finally { ResourceResolutionBudget.finish(scope, success, outputs); }
    }

    @WrapMethod(method = "getResourceStack(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;")
    private List<Resource> bootoptim$stack(ResourceLocation id, Operation<List<Resource>> original) {
        var scope = ResourceResolutionBudget.begin(this, id, "stack");
        boolean success = false;
        int outputs = 0;
        try {
            List<Resource> result = original.call(id);
            outputs = result != null ? result.size() : 0;
            success = true;
            return result;
        } finally { ResourceResolutionBudget.finish(scope, success, outputs); }
    }

    @WrapOperation(method = {
            "getResource(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
            "getResourceStack(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/PackResources;getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;", ordinal = 0))
    private IoSupplier<InputStream> bootoptim$provider(PackResources pack, PackType type,
                                                     ResourceLocation id, Operation<IoSupplier<InputStream>> original) {
        var scope = ResourceResolutionBudget.active();
        if (scope == null) return original.call(pack, type, id);
        boolean sampled = ResourceResolutionBudget.sampled(scope);
        long wallStart = sampled ? System.nanoTime() : 0;
        long cpuStart = sampled ? ResourceResolutionBudget.cpuNow() : -1;
        boolean success = false, hit = false;
        try {
            IoSupplier<InputStream> result = original.call(pack, type, id);
            hit = result != null;
            success = true;
            return result;
        } finally {
            long cpuEnd = sampled ? ResourceResolutionBudget.cpuNow() : -1;
            long wallEnd = sampled ? System.nanoTime() : 0;
            ResourceResolutionBudget.provider(scope, pack.getClass().getName(), success, hit,
                    cpuStart, cpuEnd, wallStart, wallEnd);
        }
    }
}
