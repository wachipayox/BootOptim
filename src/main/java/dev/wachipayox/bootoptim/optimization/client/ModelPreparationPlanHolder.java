package dev.wachipayox.bootoptim.optimization.client;

/** Reload-local storage attached to a resolved BlockModel. */
public interface ModelPreparationPlanHolder {
    boolean bootoptim$modelPreparationPlanCompiled();

    ModelPreparationPlanVerifier.Plan bootoptim$modelPreparationPlan();

    void bootoptim$setModelPreparationPlan(ModelPreparationPlanVerifier.Plan plan);

    void bootoptim$markModelPreparationPlanCompiled();

    boolean bootoptim$modelPreparationPlanPoisoned();

    void bootoptim$poisonModelPreparationPlan();
}
