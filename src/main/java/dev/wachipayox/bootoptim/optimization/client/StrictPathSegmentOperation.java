package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.optimization.StrictPathSegmentValidator;
import dev.wachipayox.bootoptim.profiling.client.StrictPathSegmentBudget;
import java.util.regex.Pattern;

/** Instruction-site replacement; no containing-method callback is bypassed. */
public final class StrictPathSegmentOperation {
    private StrictPathSegmentOperation() {}
    public static boolean evaluate(Pattern pattern, String segment) {
        boolean result = StrictPathSegmentValidator.ENABLED ? StrictPathSegmentValidator.guarded(pattern, segment)
                : pattern.matcher(segment).matches();
        if (StrictPathSegmentBudget.ENABLED) StrictPathSegmentBudget.rewritten();
        return result;
    }
}
