package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import java.util.concurrent.Executor;
import net.neoforged.neoforge.internal.CommonModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only boundaries around the stock NeoForge/FML lifecycle calls.
 *
 * <p>No work is skipped, reordered or moved to a different executor. Injections are fail-open and
 * the exact-pack diagnostic asserts the emitted markers rather than making startup depend on them.</p>
 */
@Mixin(CommonModLoader.class)
abstract class CommonModLoaderLifecycleTimingMixin {
    private static final String GATHER = "gather_and_initialize_mods";
    private static final String REGISTRIES = "registry_initialization";
    private static final String CONFIG = "config_loading";
    private static final String COMMON_SETUP = "common_setup";
    private static final String SIDED_SETUP = "sided_setup";
    private static final String REGISTRATION = "registration_events";
    private static final String ENQUEUE_IMC = "enqueue_imc";
    private static final String PROCESS_IMC = "process_imc";
    private static final String LOAD_COMPLETE = "load_complete";
    private static final String NETWORK_LOCK = "network_registry_lock";

    private static final String PRE_RELOAD = "pre_resource_reload";
    private static final String RESOURCE_PREP = "resource_preparation";
    private static final String POST_BARRIER = "ordered_post_barrier";

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;gatherAndInitializeMods(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeGather(Runnable periodicTask, boolean datagen, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(GATHER, PRE_RELOAD, "critical_before_reload");
    }

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;gatherAndInitializeMods(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterGather(Runnable periodicTask, boolean datagen, CallbackInfo ci) {
        FmlLifecycleProfiler.end(GATHER);
    }

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeRegistries(Runnable periodicTask, boolean datagen, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(REGISTRIES, PRE_RELOAD, "critical_before_reload");
    }

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterRegistries(Runnable periodicTask, boolean datagen, CallbackInfo ci) {
        FmlLifecycleProfiler.end(REGISTRIES);
    }

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    ordinal = 1,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeConfig(Runnable periodicTask, boolean datagen, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(CONFIG, PRE_RELOAD, "critical_before_reload");
    }

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterConfig(Runnable periodicTask, boolean datagen, CallbackInfo ci) {
        FmlLifecycleProfiler.end(CONFIG);
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeCommonSetup(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(COMMON_SETUP, RESOURCE_PREP, "conditional_preparation_gate");
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterCommonSetup(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(COMMON_SETUP);
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 1,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeSidedSetup(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(SIDED_SETUP, RESOURCE_PREP, "conditional_preparation_gate");
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterSidedSetup(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(SIDED_SETUP);
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeRegistration(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(REGISTRATION, RESOURCE_PREP, "conditional_preparation_gate");
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterRegistration(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(REGISTRATION);
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeEnqueueImc(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(ENQUEUE_IMC, POST_BARRIER, "critical_ordered_post_barrier");
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterEnqueueImc(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(ENQUEUE_IMC);
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 1,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeProcessImc(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(PROCESS_IMC, POST_BARRIER, "critical_ordered_post_barrier");
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterProcessImc(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(PROCESS_IMC);
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 2,
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeLoadComplete(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(LOAD_COMPLETE, POST_BARRIER, "critical_ordered_post_barrier");
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;dispatchParallelEvent(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/BiFunction;)V",
                    ordinal = 2,
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterLoadComplete(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(LOAD_COMPLETE);
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private static void bootoptim$beforeNetworkLock(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.begin(NETWORK_LOCK, POST_BARRIER, "critical_ordered_post_barrier");
    }

    @Inject(
            method = "finish",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;runInitTask(Ljava/lang/String;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private static void bootoptim$afterNetworkLock(Executor syncExecutor, Executor parallelExecutor, CallbackInfo ci) {
        FmlLifecycleProfiler.end(NETWORK_LOCK);
    }
}
