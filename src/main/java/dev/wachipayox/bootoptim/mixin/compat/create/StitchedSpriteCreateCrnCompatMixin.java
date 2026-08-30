package dev.wachipayox.bootoptim.mixin.compat.create;

import dev.wachipayox.bootoptim.compat.create.CreateCrnStitchedSpriteCompat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backports Ponder's StitchedSprite thread-safety fix for legacy versions when CRN is present.
 *
 * <p>NeoForge constructs independent mods in parallel. CRN eagerly creates its connected-texture entries while Create/Ponder
 * can initialize other sprite shifts on another mod-loading worker. Legacy Ponder stored those registrations in a HashMap of
 * ArrayLists. Current Ponder uses a ConcurrentHashMap with synchronized value lists; mirror that exact data-structure change
 * instead of reducing NeoForge or BootOptim parallelism globally.</p>
 */
@Pseudo
@Mixin(targets = "net.createmod.catnip.render.StitchedSprite", remap = false)
public abstract class StitchedSpriteCreateCrnCompatMixin {
    @Shadow
    @Final
    @Mutable
    private static Map<ResourceLocation, List<Object>> ALL;

    @Unique
    private static boolean bootoptim$legacyCreateCrnCache;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
    private static void bootoptim$upgradeLegacyCache(CallbackInfo ci) {
        if (!CreateCrnStitchedSpriteCompat.shouldPatch(ALL)) {
            return;
        }

        ALL = new ConcurrentHashMap<>(ALL);
        bootoptim$legacyCreateCrnCache = true;
        CreateCrnStitchedSpriteCompat.markApplied();
    }

    @Redirect(
            method = "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"),
            require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object bootoptim$threadSafeLegacyValueList(Map map, Object key, Function factory) {
        if (!bootoptim$legacyCreateCrnCache) {
            return map.computeIfAbsent(key, factory);
        }

        return map.computeIfAbsent(key, k -> {
            Object value = factory.apply(k);
            if (value instanceof List<?> list) {
                return Collections.synchronizedList((List) list);
            }
            return value;
        });
    }
}
