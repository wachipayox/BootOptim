package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.XaeroDeferredTaskProfiler;
import java.lang.StackWalker.StackFrame;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only owner boundary for FML startup deferred work.
 *
 * <p>DeferredWorkQueue sets the active ModContainer immediately before invoking the queued
 * Runnable and clears it in the finally block immediately after the Runnable returns or throws.
 * Observing those existing calls avoids wrapping, redirecting, replacing, or reordering the task.
 */
@Mixin(ModLoadingContext.class)
abstract class ModLoadingContextXaeroDeferredBoundaryMixin {
    private static final String XAERO_MOD_ID = "xaeroworldmap";
    private static final String DEFERRED_WORK_QUEUE = "net.neoforged.fml.DeferredWorkQueue";
    private static final StackWalker WALKER = StackWalker.getInstance();

    @Inject(method = "setActiveContainer", at = @At("HEAD"), require = 0)
    private void bootoptim$observeDeferredOwnerBoundary(ModContainer container, CallbackInfo ci) {
        if (!XaeroDeferredTaskProfiler.isEnabled()) {
            return;
        }

        if (container != null) {
            if (XAERO_MOD_ID.equals(container.getModId()) && bootoptim$directCallerIsDeferredWorkQueue()) {
                XaeroDeferredTaskProfiler.onDeferredTaskStart(container.getModId());
            }
            return;
        }

        if (XaeroDeferredTaskProfiler.isSamplingCurrentThread() && bootoptim$directCallerIsDeferredWorkQueue()) {
            XaeroDeferredTaskProfiler.onDeferredTaskEnd();
        }
    }

    private static boolean bootoptim$directCallerIsDeferredWorkQueue() {
        return WALKER.walk(frames -> frames
                .map(StackFrame::getClassName)
                .dropWhile(name -> name.equals(ModLoadingContext.class.getName()))
                .findFirst()
                .map(DEFERRED_WORK_QUEUE::equals)
                .orElse(false));
    }
}
