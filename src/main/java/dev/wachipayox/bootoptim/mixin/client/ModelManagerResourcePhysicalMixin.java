package dev.wachipayox.bootoptim.mixin.client;

import com.google.gson.JsonObject;
import dev.wachipayox.bootoptim.profiling.client.ResourceOpenPhysicalProfiler;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;

/** Diagnostic-only ModelManager scopes for physical resource-path attribution. */
@Mixin(ModelManager.class)
abstract class ModelManagerResourcePhysicalMixin {
    @Unique private static final ThreadLocal<Long> bootoptim$modelEnumerationStart = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> bootoptim$stateEnumerationStart = new ThreadLocal<>();

    @Inject(method = "lambda$loadBlockModels$7", at = @At("HEAD"), require = 0)
    private static void bootoptim$startModelEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        if (!ResourceOpenPhysicalProfiler.enabled()) return;
        ResourceOpenPhysicalProfiler.enterEnumeration("block_models.enumeration");
        bootoptim$modelEnumerationStart.set(System.nanoTime());
    }

    @Inject(method = "lambda$loadBlockModels$7", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModelEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        Long started = bootoptim$modelEnumerationStart.get();
        bootoptim$modelEnumerationStart.remove();
        long items = cir.getReturnValue() instanceof Map<?, ?> map ? map.size() : -1L;
        if (started != null) ResourceOpenPhysicalProfiler.recordEnumeration("block_models.enumeration", started, items);
        ResourceOpenPhysicalProfiler.clearContext();
    }

    @Inject(method = "lambda$loadBlockModels$8", at = @At("HEAD"), require = 0)
    private static void bootoptim$startModelTask(Map.Entry<ResourceLocation, Resource> entry, CallbackInfoReturnable<?> cir) {
        if (!ResourceOpenPhysicalProfiler.enabled() || entry == null) return;
        ResourceOpenPhysicalProfiler.enter("block_models", entry.getKey(), entry.getValue());
    }

    @Redirect(
            method = "lambda$loadBlockModels$8",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/model/BlockModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/renderer/block/model/BlockModel;"),
            require = 0)
    private static BlockModel bootoptim$timeModelParse(Reader reader) {
        long started = ResourceOpenPhysicalProfiler.start();
        try {
            return BlockModel.fromStream(reader);
        } finally {
            ResourceOpenPhysicalProfiler.recordCurrent("block_models.parse_inclusive", started);
        }
    }

    @Inject(method = "lambda$loadBlockModels$8", at = @At("RETURN"), require = 0)
    private static void bootoptim$endModelTask(Map.Entry<ResourceLocation, Resource> entry, CallbackInfoReturnable<?> cir) {
        ResourceOpenPhysicalProfiler.clearContext();
    }

    @Inject(method = "lambda$loadBlockStates$11", at = @At("HEAD"), require = 0)
    private static void bootoptim$startStateEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        if (!ResourceOpenPhysicalProfiler.enabled()) return;
        ResourceOpenPhysicalProfiler.enterEnumeration("block_states.enumeration");
        bootoptim$stateEnumerationStart.set(System.nanoTime());
    }

    @Inject(method = "lambda$loadBlockStates$11", at = @At("RETURN"), require = 0)
    private static void bootoptim$endStateEnumeration(ResourceManager manager, CallbackInfoReturnable<?> cir) {
        Long started = bootoptim$stateEnumerationStart.get();
        bootoptim$stateEnumerationStart.remove();
        long items = cir.getReturnValue() instanceof Map<?, ?> map ? map.size() : -1L;
        if (started != null) ResourceOpenPhysicalProfiler.recordEnumeration("block_states.enumeration", started, items);
        ResourceOpenPhysicalProfiler.clearContext();
    }

    @Inject(method = "lambda$loadBlockStates$12", at = @At("HEAD"), require = 0)
    private static void bootoptim$startStateTask(Map.Entry<ResourceLocation, List<Resource>> entry, CallbackInfoReturnable<?> cir) {
        if (!ResourceOpenPhysicalProfiler.enabled() || entry == null) return;
        ResourceOpenPhysicalProfiler.enter("block_states", entry.getKey(), null);
    }

    @Redirect(
            method = "lambda$loadBlockStates$12",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;openAsReader()Ljava/io/BufferedReader;"),
            require = 0)
    private static BufferedReader bootoptim$selectStatePack(Resource resource) throws IOException {
        ResourceOpenPhysicalProfiler.setCurrentResource(resource);
        return resource.openAsReader();
    }

    @Redirect(
            method = "lambda$loadBlockStates$12",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/GsonHelper;parse(Ljava/io/Reader;)Lcom/google/gson/JsonObject;"),
            require = 0)
    private static JsonObject bootoptim$timeStateParse(Reader reader) {
        long started = ResourceOpenPhysicalProfiler.start();
        try {
            return GsonHelper.parse(reader);
        } finally {
            ResourceOpenPhysicalProfiler.recordCurrent("block_states.parse_inclusive", started);
        }
    }

    @Inject(method = "lambda$loadBlockStates$12", at = @At("RETURN"), require = 0)
    private static void bootoptim$endStateTask(Map.Entry<ResourceLocation, List<Resource>> entry, CallbackInfoReturnable<?> cir) {
        ResourceOpenPhysicalProfiler.clearContext();
    }
}
