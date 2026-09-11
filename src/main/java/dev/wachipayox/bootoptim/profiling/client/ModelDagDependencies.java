package dev.wachipayox.bootoptim.profiling.client;

import java.util.concurrent.atomic.AtomicLong;

/** Task-id handoff for real same-generation lexical model-stage dependencies. */
public final class ModelDagDependencies {
    private static final AtomicLong LAST_BAKERY_PREP_TASK = new AtomicLong();

    private ModelDagDependencies() {
    }

    public static void rememberBakeryPreparation(long taskId) {
        if (taskId != 0L) {
            LAST_BAKERY_PREP_TASK.set(taskId);
        }
    }

    public static long[] bakeryPreparationDependency() {
        long taskId = LAST_BAKERY_PREP_TASK.get();
        return taskId == 0L ? null : new long[] {taskId};
    }
}
