package dev.wachipayox.bootoptim.optimization.client;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Tiny allocation-free iterator for hot paths that need to expose zero or one preselected candidate.
 *
 * <p>This lives outside the Mixin package deliberately: helper classes referenced by transformed Minecraft
 * classes must remain ordinary loadable BootOptim classes rather than nested classes owned by a mixin config.</p>
 */
public final class ReusableSingletonIterator implements Iterator<Object> {
    private Object candidate;
    private boolean available;

    public Iterator<?> reset(Object candidate) {
        this.candidate = candidate;
        this.available = true;
        return this;
    }

    @Override
    public boolean hasNext() {
        return available;
    }

    @Override
    public Object next() {
        if (!available) {
            throw new NoSuchElementException();
        }
        available = false;
        return candidate;
    }
}
