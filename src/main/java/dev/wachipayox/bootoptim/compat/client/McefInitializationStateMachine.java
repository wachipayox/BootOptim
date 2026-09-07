package dev.wachipayox.bootoptim.compat.client;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-level owner/completion state for the MCEF first-consumer coordinator.
 *
 * <p>This class deliberately has no Minecraft, MCEF, or native dependencies so its concurrency
 * contract can be exercised deterministically. The real initializer and thread handoff remain in
 * {@link McefFirstConsumerDefer}.</p>
 */
final class McefInitializationStateMachine {
    enum State {
        ARMED,
        DEFERRED,
        FORCING_BY_CONSUMER,
        INITIALIZING,
        COMPLETE,
        FAILED,
        ABORTED
    }

    enum ConsumerAction {
        BYPASS,
        INITIALIZE,
        WAIT
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.ARMED);
    private final AtomicReference<Thread> initializerThread = new AtomicReference<>();
    private final CompletableFuture<Boolean> completion = new CompletableFuture<>();

    State state() {
        return state.get();
    }

    Thread initializerThread() {
        return initializerThread.get();
    }

    boolean markDeferred() {
        return state.compareAndSet(State.ARMED, State.DEFERRED);
    }

    void observeReady() {
        state.set(State.COMPLETE);
        initializerThread.set(null);
        completion.complete(true);
    }

    /**
     * Abort only while no real initializer is executing. A waiter or caller must never publish
     * ABORTED behind an in-flight native initializer.
     */
    boolean abortBeforeInitialization() {
        while (true) {
            State current = state.get();
            if (current == State.INITIALIZING
                    || current == State.COMPLETE
                    || current == State.FAILED
                    || current == State.ABORTED) {
                return false;
            }
            if (state.compareAndSet(current, State.ABORTED)) {
                initializerThread.set(null);
                completion.complete(false);
                return true;
            }
        }
    }

    ConsumerAction beforeConsumer(Thread currentThread, boolean mcefReady) {
        while (true) {
            State current = state.get();
            if (current == State.DEFERRED) {
                if (state.compareAndSet(State.DEFERRED, State.FORCING_BY_CONSUMER)) {
                    return ConsumerAction.INITIALIZE;
                }
                continue;
            }
            if (current == State.FORCING_BY_CONSUMER) {
                return ConsumerAction.WAIT;
            }
            if (current == State.INITIALIZING) {
                if (initializerThread.get() == currentThread) {
                    if (mcefReady) {
                        return ConsumerAction.BYPASS;
                    }
                    throw new IllegalStateException(
                            "MCEF initializer owner re-entered a guarded consumer before MCEF published readiness");
                }
                return ConsumerAction.WAIT;
            }
            return ConsumerAction.BYPASS;
        }
    }

    /** Publish the owner immediately before the one real synchronous initializer call. */
    synchronized boolean beginInitialization(Thread currentThread) {
        if (state.get() != State.FORCING_BY_CONSUMER) {
            return false;
        }
        if (initializerThread.get() != null) {
            return false;
        }
        initializerThread.set(currentThread);
        state.set(State.INITIALIZING);
        return true;
    }

    void finishInitialization(boolean result, Throwable failure) {
        Thread currentThread = Thread.currentThread();
        if (initializerThread.get() != currentThread || state.get() != State.INITIALIZING) {
            throw new IllegalStateException("Only the active MCEF initializer owner may publish completion");
        }

        state.set(result && failure == null ? State.COMPLETE : State.FAILED);
        initializerThread.compareAndSet(currentThread, null);
        if (failure != null) {
            completion.completeExceptionally(failure);
        } else {
            completion.complete(result);
        }
    }

    boolean awaitCompletion() {
        return completion.join();
    }

    static Throwable unwrapInitializerThrowable(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }
}
