package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.EmfExpressionParsing;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** EMF animation-expression parser; see {@link EmfExpressionParsing}. Not pinned to an EMF version. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.expression_tree.MathExpressionParser", remap = false)
abstract class EmfExpressionParserMixin {
    private static final ThreadLocal<Object> bootoptim$pendingPass = new ThreadLocal<>();

    @Shadow(remap = false) @Final private String originalExpression;

    /** The expression's characters, read straight from the string instead of a boxed stream. */
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;iterator()Ljava/util/Iterator;",
            remap = false), require = 0, remap = false)
    private Iterator<?> bootoptim$characters(Stream<?> stream, Operation<Iterator<?>> original) {
        String text = originalExpression;
        if (text == null || !EmfExpressionParsing.CHARACTERS.enabled()) {
            return original.call(stream);
        }
        if (EmfExpressionParsing.CHARACTERS.verifyThisCall()) {
            return EmfExpressionParsing.verifyCharacters(text, original.call(stream));
        }
        return EmfExpressionParsing.characters(text);
    }

    /** Variable names are first tried as numbers; reject the impossible ones without a stack trace. */
    @WrapOperation(method = "readVariableOrConstant", at = @At(value = "INVOKE", target = "Ljava/lang/Float;parseFloat(Ljava/lang/String;)F",
            remap = false), require = 0, remap = false)
    private float bootoptim$parseFloat(String token, Operation<Float> original) {
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.NUMERIC_PROBE;
        if (!guard.enabled() || !EmfExpressionParsing.definitelyNotNumber(token)) {
            return original.call(token);
        }
        if (guard.verifyThisCall()) {
            float parsed;
            try {
                parsed = original.call(token);
            } catch (NumberFormatException expected) {
                guard.confirmed();
                throw expected;
            }
            guard.mismatch("parsed_token");
            return parsed;
        }
        throw EmfExpressionParsing.notANumber();
    }

    @WrapOperation(method = "readVariableOrConstant", at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z",
            remap = false), require = 0, remap = false)
    private boolean bootoptim$matches(String input, String regex, Operation<Boolean> original) {
        if (input == null || regex == null || !EmfExpressionParsing.patternCacheEnabled()) {
            return original.call(input, regex);
        }
        return EmfExpressionParsing.matches(input, regex);
    }

    /** A pass whose operators are all absent returns its input; skip building the stream for it. */
    @Inject(method = "optimiseTheseActionsIntoBinaryComponents", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void bootoptim$skipEmptyPass(List<?> actions, @Coerce Object components, CallbackInfoReturnable<Object> cir) {
        bootoptim$pendingPass.remove();
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.OPERATOR_PASS;
        if (!guard.enabled() || actions == null || !(components instanceof List<?> list)
                || !EmfExpressionParsing.noneOf(actions, list)) {
            return;
        }
        if (guard.verifyThisCall()) {
            bootoptim$pendingPass.set(components);
            return;
        }
        cir.setReturnValue(components);
    }

    @Inject(method = "optimiseTheseActionsIntoBinaryComponents", at = @At("RETURN"), require = 0, remap = false)
    private void bootoptim$checkEmptyPass(List<?> actions, @Coerce Object components, CallbackInfoReturnable<Object> cir) {
        Object expected = bootoptim$pendingPass.get();
        if (expected == null) {
            return;
        }
        bootoptim$pendingPass.remove();
        if (cir.getReturnValue() == expected) {
            EmfExpressionParsing.OPERATOR_PASS.confirmed();
        } else {
            EmfExpressionParsing.OPERATOR_PASS.mismatch("pass_changed_components");
        }
    }
}
