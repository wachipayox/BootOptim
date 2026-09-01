package dev.wachipayox.bootoptim.optimization.client;

/**
 * Internal bridge mixed into BlockModel so a compiled ElementsModel traversal plan can live on the
 * persistent model that owns the actual element list rather than on NeoForge's transient ElementsModel.
 */
public interface CompiledElementsPlanHolder {
    CompiledElementsBakePlan bootoptim$getCompiledElementsPlan();

    void bootoptim$setCompiledElementsPlan(CompiledElementsBakePlan plan);

    boolean bootoptim$hasSeenElementsBake();

    void bootoptim$markElementsBakeSeen();
}
