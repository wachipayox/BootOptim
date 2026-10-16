package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.EmfExpressionParsing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** EMF variable factories match every variable name against a fixed regex; reuse the compiled pattern. */
@Pseudo
@Mixin(targets = {
        "traben.entity_model_features.models.animation.math.variables.factories.ModelPartVariableFactory",
        "traben.entity_model_features.models.animation.math.variables.factories.ModelVariableFactory",
        "traben.entity_model_features.models.animation.math.variables.factories.GlobalVariableFactory",
        "traben.entity_model_features.models.animation.math.variables.factories.RenderVariableFactory"}, remap = false)
abstract class EmfVariablePatternMixin {
    @WrapOperation(method = "createsThisVariable", at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z",
            remap = false), require = 0, remap = false)
    private boolean bootoptim$matches(String input, String regex, Operation<Boolean> original) {
        if (input == null || regex == null || !EmfExpressionParsing.patternCacheEnabled()) {
            return original.call(input, regex);
        }
        return EmfExpressionParsing.matches(input, regex);
    }
}
