package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.profiling.client.PathPackEnumerationProbe;
import java.util.Set;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Diagnostic-only exact-class observation. Original calls, returned sets,
 * suppliers and output callback invocation order are preserved. The temporary
 * output wrapper is NEVER a production optimization or semantic oracle.
 */
@Mixin(PathPackResources.class)
abstract class PathPackEnumerationProbeMixin {
    @WrapMethod(method = "listResources")
    private void bootoptim$profileList(PackType type, String namespace, String path,
            PackResources.ResourceOutput output, Operation<Void> original) {
        if (!PathPackEnumerationProbe.ENABLED || ((Object) this).getClass() != PathPackResources.class) {
            original.call(type, namespace, path, output);
            return;
        }
        PathPackEnumerationProbe.entering();
        long startWall = System.nanoTime();
        long startCpu = PathPackEnumerationProbe.cpu();
        long[] callbackCpu = {0L};
        long[] outputs = {0L};
        PackResources.ResourceOutput delegated = (location, supplier) -> {
            long callbackStart = PathPackEnumerationProbe.cpu();
            try {
                output.accept(location, supplier);
            } finally {
                long callbackEnd = PathPackEnumerationProbe.cpu();
                if (callbackStart >= 0 && callbackEnd >= callbackStart) {
                    callbackCpu[0] += callbackEnd - callbackStart;
                } else {
                    callbackCpu[0] = -1L;
                }
                outputs[0]++;
            }
        };
        boolean success = false;
        try {
            original.call(type, namespace, path, delegated);
            success = true;
        } finally {
            long endCpu = PathPackEnumerationProbe.cpu();
            PathPackEnumerationProbe.listCompleted(success, startCpu, endCpu,
                    callbackCpu[0], outputs[0], System.nanoTime() - startWall);
        }
    }

    @WrapMethod(method = "getNamespaces")
    private Set<String> bootoptim$profileNamespaces(PackType type, Operation<Set<String>> original) {
        if (!PathPackEnumerationProbe.ENABLED || ((Object) this).getClass() != PathPackResources.class) {
            return original.call(type);
        }
        PathPackEnumerationProbe.entering();
        long startCpu = PathPackEnumerationProbe.cpu();
        boolean success = false;
        try {
            Set<String> result = original.call(type);
            success = true;
            return result;
        } finally {
            PathPackEnumerationProbe.namespaceCompleted(success, startCpu, PathPackEnumerationProbe.cpu());
        }
    }
}
