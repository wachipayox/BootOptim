package dev.wachipayox.bootoptim.optimization;

import java.util.LinkedHashSet;
import java.util.Set;

/** Per-invocation local, no cache/pool, callbacks and current model graphs stay stock. */
public final class ParentCycleSet {
    private ParentCycleSet() {}
    public static Set<?> ensure(Set<?> current) {
        return current == null ? new LinkedHashSet<>() : current;
    }
}
