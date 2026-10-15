package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.EmfExistenceIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Entity Model Features' per-reload existence cache. Not pinned to an EMF version: each injector
 * targets a call by its owner and descriptor, so a refactor that removes the call leaves stock
 * behaviour and the post-apply check logs {@code [WBOMBO-COMPAT] FALLBACK}.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.utils.EMFResourceCaching", remap = false)
abstract class EmfExistenceIndexMixin {
    /** EMF's reload boundary: every cached answer of the previous generation is dropped. */
    @Inject(method = "clearCache", at = @At("HEAD"), require = 0, remap = false)
    private static void bootoptim$clearEmfExistenceIndex(CallbackInfo ci) {
        EmfExistenceIndex.clear();
    }

    /** The stock directory listing, taken once per prefix; recorded only if provably complete. */
    @WrapOperation(method = "scanPrefix", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ResourceManager;listResources(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Map;",
            remap = false), require = 0, remap = false)
    private static Map<ResourceLocation, Resource> bootoptim$recordEmfListing(ResourceManager manager, String prefix,
            Predicate<ResourceLocation> filter, Operation<Map<ResourceLocation, Resource>> original) {
        long generation = EmfExistenceIndex.generation();
        Map<ResourceLocation, Resource> listing = original.call(manager, prefix, filter);
        EmfExistenceIndex.record(manager, prefix, filter, listing.keySet(), generation);
        return listing;
    }

    /** The absent-candidate probe: answered from the listing, with a sampled stock cross-check. */
    @WrapOperation(method = "resourceExists", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResource(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
            remap = false), require = 0, remap = false)
    private static Optional<Resource> bootoptim$answerAbsentFromListing(ResourceManager manager,
            ResourceLocation location, Operation<Optional<Resource>> original) {
        if (!EmfExistenceIndex.enabled() || !EmfExistenceIndex.knownAbsent(manager, location)) {
            return original.call(manager, location);
        }
        if (EmfExistenceIndex.shouldVerify()) {
            long start = System.nanoTime();
            Optional<Resource> stock = original.call(manager, location);
            long elapsed = System.nanoTime() - start;
            if (stock.isPresent()) {
                EmfExistenceIndex.mismatch(location);
            } else {
                EmfExistenceIndex.confirmed(elapsed);
            }
            return stock;
        }
        EmfExistenceIndex.served();
        return Optional.empty();
    }
}
