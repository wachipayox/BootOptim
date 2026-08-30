package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Diagnostic only: enables Minecraft's own ProfiledReloadInstance while BootOptim startup
 * reporting/profiling is active. Listener ordering, executors and reload data are unchanged.
 */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceProfilingMixin {
    private static final AtomicBoolean BOOTOPTIM$REPORTED = new AtomicBoolean();

    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
    private static boolean bootoptim$enableProfiledReload(boolean profiled) {
        boolean enabled = profiled || StartupProfiler.isEnabled();
        if (enabled && !profiled && BOOTOPTIM$REPORTED.compareAndSet(false, true)) {
            logger().info("BOOTOPTIM_RESOURCE_PROFILER status=enabled strategy=minecraft_profiled_reload_instance");
        }
        return enabled;
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }
}
