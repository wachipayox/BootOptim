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
    private static final ThreadLocal<Deque<ArrayList<String>>> LISTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private MaterialResolutionListPool() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    public static void begin() {
        if (!enabled()) {
            return;
        }
        Deque<ArrayList<String>> stack = LISTS.get();
        ArrayList<String> list = stack.pollFirst();
        if (list == null) {
            list = new ArrayList<>();
        } else {
            list.clear();
        }
        stack.addFirst(list);
    }

    public static ArrayList<String> borrow() {
        if (!enabled()) {
            // The redirect is active even when the experiment is disabled;
            // preserve vanilla's concrete return type and allocation path.
            return new ArrayList<>();
        }
        Deque<ArrayList<String>> stack = LISTS.get();
        ArrayList<String> list = stack.peekFirst();
        if (list == null) {
            // Fail open if a loader invokes the redirected site unexpectedly
            // without passing through the method HEAD hook.
            list = new ArrayList<>();
            stack.addFirst(list);
        }
        return list;
    }

    public static void end() {
        if (!enabled()) {
            return;
        }
        Deque<ArrayList<String>> stack = LISTS.get();
        ArrayList<String> list = stack.pollFirst();
        if (list != null) {
            list.clear();
        }
    }
}
