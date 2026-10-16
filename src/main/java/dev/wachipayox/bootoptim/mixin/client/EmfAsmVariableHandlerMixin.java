package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.optimization.client.EmfExpressionParsing;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** EMF ASM variable slots: one list scan per lookup and a linear end-of-parse check. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.asm.ASMVariableHandler", remap = false)
abstract class EmfAsmVariableHandlerMixin {
    @Shadow(remap = false) @Final private Stack<Boolean> booleanScopeStack;
    @Shadow(remap = false) @Final private List<String> floatVarList;
    @Shadow(remap = false) @Final private List<String> boolVarList;
    @Shadow(remap = false) @Final private Set<String> readVarNames;
    @Shadow(remap = false) @Final private Set<String> writeVarNames;
    @Shadow(remap = false) private int localVarIndex;

    /** Stock: {@code contains} then {@code indexOf}, two scans of the same list. */
    @WrapMethod(method = "getAndAssignVarIndex", require = 0, remap = false)
    private int bootoptim$varIndex(String name, boolean reading, Operation<Integer> original) {
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.VARIABLE_INDEX;
        if (!guard.enabled() || booleanScopeStack.isEmpty()) {
            return original.call(name, reading);
        }
        List<String> list = booleanScopeStack.peek() ? boolVarList : floatVarList;
        if (guard.verifyThisCall()) {
            int existing = list.indexOf(name);
            int expected = existing >= 0 ? existing : list.size();
            int stock = original.call(name, reading);
            if (stock == expected && stock < list.size() && Objects.equals(list.get(stock), name)) {
                guard.confirmed();
            } else {
                guard.mismatch("variable_index");
            }
            return stock;
        }
        if (reading) {
            readVarNames.add(name);
        } else {
            writeVarNames.add(name);
        }
        int index = list.indexOf(name);
        if (index < 0) {
            list.add(name);
            index = list.size() - 1;
        }
        return index;
    }

    /** Stock checks float/bool name overlap with {@code List.contains} per float name. */
    @WrapMethod(method = "verifyEndOfParse", require = 0, remap = false)
    private void bootoptim$verifyEndOfParse(Operation<Void> original) {
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.VARIABLE_VALIDATION;
        if (!guard.enabled() || !booleanScopeStack.isEmpty() || localVarIndex != 1) {
            original.call();
            return;
        }
        Set<String> bools = new HashSet<>(boolVarList);
        for (String name : floatVarList) {
            if (bools.contains(name)) {
                original.call(); // Stock error message and exception.
                return;
            }
        }
        if (guard.verifyThisCall()) {
            try {
                original.call();
            } catch (Throwable failure) {
                guard.mismatch("validation_failed");
                throw EmfExpressionParsing.rethrow(failure);
            }
            guard.confirmed();
        }
    }
}
