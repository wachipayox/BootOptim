package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.optimization.client.EmfExpressionParsing;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** EMF method-call argument splitting without a boxed character stream; see {@link EmfExpressionParsing}. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.expression_tree.MathMethod", remap = false)
abstract class EmfMethodArgumentsMixin {
    @WrapMethod(method = "getArgsList", require = 0, remap = false)
    private static List<String> bootoptim$arguments(String args, Operation<List<String>> original) {
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.METHOD_ARGUMENTS;
        if (args == null || !guard.enabled()) {
            return original.call(args);
        }
        List<String> fast = EmfExpressionParsing.splitArguments(args);
        if (guard.verifyThisCall()) {
            List<String> stock = original.call(args);
            if (fast.equals(stock)) {
                guard.confirmed();
            } else {
                guard.mismatch("arguments_differ");
            }
            return stock;
        }
        return fast;
    }
}
