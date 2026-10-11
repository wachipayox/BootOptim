package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.ModelBakery;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** One clock around the real top-level parent traversal; no individual-call observer. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryParentCycleStageMixin {
    @WrapOperation(method="<init>", at=@At(value="INVOKE",target="Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"), require=1)
    private void bootoptim$parents(Collection<?> models, Consumer<Object> visitor, Operation<Void> original) {
        var cpu=ManagementFactory.getThreadMXBean();
        var alloc=cpu instanceof com.sun.management.ThreadMXBean a ? a : null;
        long bytesBefore=alloc!=null && alloc.isThreadAllocatedMemoryEnabled() ? alloc.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        long cpuBefore=cpu.isThreadCpuTimeEnabled() ? cpu.getCurrentThreadCpuTime() : -1;
        long wallBefore=System.nanoTime();
        boolean finished=false;
        try { original.call(models,visitor); finished=true; }
        finally {
            long wall=System.nanoTime()-wallBefore;
            long cpuAfter=cpu.isThreadCpuTimeEnabled() ? cpu.getCurrentThreadCpuTime() : -1;
            long bytesAfter=alloc!=null && alloc.isThreadAllocatedMemoryEnabled() ? alloc.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
            LoggerFactory.getLogger("BootOptim/ParentCycleSet").info("BOOTOPTIM_PARENT_CYCLE_STAGE cpu_ns={} wall_ns={} allocated_bytes={} finished={}",
                    cpuBefore>=0 && cpuAfter>=cpuBefore ? cpuAfter-cpuBefore : -1, wall,
                    bytesBefore>=0 && bytesAfter>=bytesBefore ? bytesAfter-bytesBefore : -1,finished);
        }
    }
}
