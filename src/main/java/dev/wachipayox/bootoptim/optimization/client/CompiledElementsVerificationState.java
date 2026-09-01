package dev.wachipayox.bootoptim.optimization.client;

import java.util.ArrayList;
import java.util.List;

/** Runtime state referenced by the mixed target; intentionally lives outside the Mixin package. */
public final class CompiledElementsVerificationState {
    public final String modelName;
    public final List<CompiledElementsBakePlan.QuadRecord> candidate;
    public final ArrayList<CompiledElementsBakePlan.QuadRecord> stock;
    public final long stockStartNanos;

    public CompiledElementsVerificationState(
            String modelName,
            List<CompiledElementsBakePlan.QuadRecord> candidate,
            ArrayList<CompiledElementsBakePlan.QuadRecord> stock,
            long stockStartNanos) {
        this.modelName = modelName;
        this.candidate = candidate;
        this.stock = stock;
        this.stockStartNanos = stockStartNanos;
    }
}
