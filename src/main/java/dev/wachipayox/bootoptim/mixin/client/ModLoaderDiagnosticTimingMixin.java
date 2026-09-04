package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlGatherProfiler;
import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import java.util.List;
import java.util.concurrent.Executor;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforgespi.locating.IModFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only second-stage timings inside FML ModLoader. */
@Mixin(ModLoader.class)
abstract class ModLoaderDiagnosticTimingMixin {
    private static final String PRE_RELOAD = "pre_resource_reload";
    private static final String CRITICAL = "critical_before_reload";

    @Inject(method = "gatherAndInitializeMods", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginGather(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlGatherProfiler.beginGather();
    }

    @Inject(
            method = "gatherAndInitializeMods",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/modscan/BackgroundScanHandler;waitForScanToComplete(Ljava/lang/Runnable;)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeScanWait(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlGatherProfiler.beginScanWait();
        FmlLifecycleProfiler.begin("gather_scan_wait", PRE_RELOAD, CRITICAL);
    }

    @Inject(
            method = "gatherAndInitializeMods",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/modscan/BackgroundScanHandler;waitForScanToComplete(Ljava/lang/Runnable;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterScanWait(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlLifecycleProfiler.end("gather_scan_wait");
        FmlGatherProfiler.endScanWait();
    }

    @Inject(method = "buildMods", at = @At("HEAD"), require = 0)
    private static void bootoptim$beforeBuildMods(IModFile modFile, CallbackInfoReturnable<List<ModContainer>> cir) {
        FmlGatherProfiler.beginBuildMods(modFile);
    }

    @Inject(method = "buildMods", at = @At("RETURN"), require = 0)
    private static void bootoptim$afterBuildMods(IModFile modFile, CallbackInfoReturnable<List<ModContainer>> cir) {
        FmlGatherProfiler.endBuildMods();
    }

    @Inject(
            method = "constructMods",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/Consumer;)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeParallelConstruction(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlGatherProfiler.beginParallelConstruction();
        FmlLifecycleProfiler.begin("gather_parallel_construction", PRE_RELOAD, CRITICAL);
    }

    @Inject(
            method = "constructMods",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/Consumer;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterParallelConstruction(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlLifecycleProfiler.end("gather_parallel_construction");
        FmlGatherProfiler.endParallelConstruction();
    }

    @Inject(
            method = "constructMods",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;waitForTask(Ljava/lang/String;Ljava/lang/Runnable;Ljava/util/concurrent/CompletableFuture;)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeConstructionDeferred(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlGatherProfiler.beginDeferredConstruction();
        FmlLifecycleProfiler.begin("gather_construction_deferred", PRE_RELOAD, CRITICAL);
    }

    @Inject(
            method = "constructMods",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;waitForTask(Ljava/lang/String;Ljava/lang/Runnable;Ljava/util/concurrent/CompletableFuture;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterConstructionDeferred(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlLifecycleProfiler.end("gather_construction_deferred");
        FmlGatherProfiler.endDeferredConstruction();
    }

    @Inject(method = "gatherAndInitializeMods", at = @At("RETURN"), require = 0)
    private static void bootoptim$endGather(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask, CallbackInfo ci) {
        FmlGatherProfiler.endGather();
    }

    @Inject(
            method = "postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$beforeRegisterDispatch(Event event, CallbackInfo ci) {
        if (event instanceof RegisterEvent registerEvent) {
            String registry = FmlRegistryProfiler.registryName(registerEvent);
            FmlRegistryProfiler.beginRegisterEvent(registerEvent);
            FmlLifecycleProfiler.begin("register_event:" + registry, PRE_RELOAD, CRITICAL);
        }
    }

    @Inject(
            method = "postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
            at = @At("RETURN"),
            require = 0)
    private static void bootoptim$afterRegisterDispatch(Event event, CallbackInfo ci) {
        if (event instanceof RegisterEvent registerEvent) {
            String registry = FmlRegistryProfiler.registryName(registerEvent);
            FmlLifecycleProfiler.end("register_event:" + registry);
            FmlRegistryProfiler.endRegisterEvent(registerEvent);
        }
    }
}
