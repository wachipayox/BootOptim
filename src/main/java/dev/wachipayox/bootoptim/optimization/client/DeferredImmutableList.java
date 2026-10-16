package dev.wachipayox.bootoptim.optimization.client;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Supplier;

/** Immutable list with a known size; the elements are computed once, on first access, under a lock. */
final class DeferredImmutableList<T> extends AbstractList<T> implements RandomAccess {
    private final int count;
    private volatile List<T> ready;
    private Supplier<List<T>> factory;

    DeferredImmutableList(int count, Supplier<List<T>> factory) {
        this.count = count;
        this.factory = Objects.requireNonNull(factory);
    }

    @Override public int size() { return count; }

    @Override public T get(int index) {
        Objects.checkIndex(index, count);
        return materialize().get(index);
    }

    private List<T> materialize() {
        List<T> value = ready;
        if (value != null) {
            return value;
        }
        synchronized (this) {
            if (ready == null) {
                List<T> built = List.copyOf(factory.get());
                if (built.size() != count) {
                    throw new IllegalStateException("rotation_size_changed");
                }
                ready = built;
                factory = null;
            }
            return ready;
        }
    }
}
