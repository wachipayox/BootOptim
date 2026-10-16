package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.EmfExpressionParsing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** EMF swaps a file name's suffix with {@code replaceFirst(name + "$", ...)}; do the literal swap directly. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.utils.EMFDirectoryHandler", remap = false)
abstract class EmfDirectorySuffixMixin {
    @WrapOperation(method = {"getRelativeDirectoryLocationNoValidation", "getRelativeFilePossiblyEMFOverridden"},
            at = @At(value = "INVOKE", target = "Ljava/lang/String;replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    remap = false), require = 0, remap = false)
    private String bootoptim$suffix(String path, String regex, String replacement, Operation<String> original) {
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.DIRECTORY_SUFFIX;
        String fast = guard.enabled() ? EmfExpressionParsing.replaceLiteralSuffix(path, regex, replacement) : null;
        if (fast == null) {
            return original.call(path, regex, replacement);
        }
        if (guard.verifyThisCall()) {
            String stock = original.call(path, regex, replacement);
            if (fast.equals(stock)) {
                guard.confirmed();
            } else {
                guard.mismatch("suffix_differs");
            }
            return stock;
        }
        return fast;
    }
}
