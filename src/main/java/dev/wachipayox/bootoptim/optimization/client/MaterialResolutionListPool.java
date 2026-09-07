package dev.wachipayox.bootoptim.optimization.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

/**
 * Experiment-only pool for the temporary texture-reference list allocated by
 * vanilla {@code BlockModel#getMaterial}. The list is scoped to one call and
 * one thread; it is never shared across models or reloads.
 */
public final class MaterialResolutionListPool {
    private static final String PROPERTY = "boot_optim.materialResolutionListPool";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final ThreadLocal<State> STATES = ThreadLocal.withInitial(State::new);

    private static final class State {
        private final Deque<ArrayList<String>> available = new ArrayDeque<>();
        private final Deque<ArrayList<String>> active = new ArrayDeque<>();
    }

    private MaterialResolutionListPool() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void begin() {
        if (!enabled()) {
            return;
        }
        State state = STATES.get();
        ArrayList<String> list = state.available.pollFirst();
        if (list == null) {
            list = new ArrayList<>();
        } else {
            list.clear();
        }
        state.active.addFirst(list);
    }

    public static ArrayList<String> borrow() {
        if (!enabled()) {
            // The redirect is active even when the experiment is disabled;
            // preserve vanilla's concrete return type and allocation path.
            return new ArrayList<>();
        }
        State state = STATES.get();
        ArrayList<String> list = state.active.peekFirst();
        if (list == null) {
            // Fail open if a loader invokes the redirected site unexpectedly
            // without passing through the method HEAD hook.
            list = new ArrayList<>();
            state.active.addFirst(list);
        }
        return list;
    }

    public static void end() {
        if (!enabled()) {
            return;
        }
        State state = STATES.get();
        ArrayList<String> list = state.active.pollFirst();
        if (list != null) {
            list.clear();
            state.available.addFirst(list);
        }
    }
}
