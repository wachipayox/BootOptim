package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.client.TrialFeatureGate;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collector;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/** Reuses union construction only after consuming and validating every current stream element. */
public final class ValidatedDependencyUnion {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.multipartValidatedUnion");
    private static final boolean VERIFY = Boolean.getBoolean("boot_optim.verifyMultipartUnion");
    private static final boolean PROFILE = Boolean.getBoolean("boot_optim.profileMultipartUnion");
    private static final int MAX_ELEMENTS = 4096;
    private static final int MAX_MODELS = 4096;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ValidatedDependencyUnion() {}

    public static boolean inScope() { return CURRENT.get() != null; }
    private static boolean enabled() { return ENABLED && TrialFeatureGate.allows(TrialFeatureGate.MULTIPART); }

    public static void loadAll(Runnable original) {
        if (!ENABLED && !VERIFY && !PROFILE) { original.run(); return; }
        Scope previous = CURRENT.get(), scope = new Scope();
        CURRENT.set(scope);
        var threads = PROFILE ? ManagementFactory.getThreadMXBean() : null;
        long cpu = PROFILE && threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
        long start = PROFILE ? System.nanoTime() : 0;
        boolean success = false;
        try { original.run(); success = true; }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            long elapsed = PROFILE ? System.nanoTime() - start : -1;
            long usedCpu = cpu >= 0 ? threads.getCurrentThreadCpuTime() - cpu : -1;
            LogUtils.getLogger().info("BOOTOPTIM_MULTIPART_UNION success={} enabled={} verify={} attempts={} hits={} misses={} unsafe={} oversized={} parallel={} plans={} mismatches={} load_all_wall_ns={} load_all_cpu_ns={}",
                    success, enabled(), VERIFY, scope.attempts, scope.hits, scope.misses, scope.unsafe,
                    scope.oversized, scope.parallel, scope.plans.size(), scope.mismatches, elapsed, usedCpu);
        }
    }

    public static <T> Collector<T, ?, Set<T>> collector(Object owner, Class<?> exactElementClass,
                                                      Collector<T, ?, Set<T>> stock) {
        Scope scope = (enabled() || VERIFY) ? CURRENT.get() : null;
        if (scope == null) return stock;
        scope.attempts++;
        @SuppressWarnings("unchecked") Plan<T> plan = (Plan<T>) scope.plans.get(owner);
        return new GuardedCollector<>(stock, scope, owner, exactElementClass, plan);
    }

    /** Preserve stock parallel collector/combiner ownership; this experiment is sequential only. */
    public static <T> Collector<T, ?, Set<T>> forStream(Collector<T, ?, Set<T>> collector, boolean parallel) {
        if (parallel && collector instanceof GuardedCollector<?> guarded) {
            guarded.scope.parallel++;
            @SuppressWarnings("unchecked") Collector<T, ?, Set<T>> stock = (Collector<T, ?, Set<T>>) (Collector<?, ?, ?>) guarded.stock;
            return stock;
        }
        return collector;
    }

    private record GuardedCollector<T>(Collector<T, ?, Set<T>> stock, Scope scope, Object owner,
                                      Class<?> exactClass, Plan<T> plan) implements Collector<T, Accumulator<T>, Set<T>> {
        public Supplier<Accumulator<T>> supplier() { return () -> new Accumulator<>(plan, exactClass); }
        public BiConsumer<Accumulator<T>, T> accumulator() { return Accumulator::accept; }
        public BinaryOperator<Accumulator<T>> combiner() { return Accumulator::merge; }
        public Function<Accumulator<T>, Set<T>> finisher() { return accumulated -> accumulated.finish(owner, scope); }
        public Set<Characteristics> characteristics() { return Set.of(Characteristics.UNORDERED); }
    }

    private record Plan<T>(List<T> sequence, List<T> unique) {}

    private static final class Scope {
        final Map<Object, Plan<?>> plans = new IdentityHashMap<>();
        long attempts, hits, misses, unsafe, oversized, parallel, mismatches;
    }

    private static final class Accumulator<T> {
        private final Plan<T> prior;
        private final Class<?> exactClass;
        private int index;
        private boolean matching, safe = true, combined;
        private HashSet<T> set;
        private ArrayList<T> sequence, unique;
        private final ArrayList<T> verification = VERIFY ? new ArrayList<>() : null;

        Accumulator(Plan<T> prior, Class<?> exactClass) {
            this.prior = prior;
            this.exactClass = exactClass;
            matching = prior != null;
            if (!matching) beginBuild();
        }

        private void beginBuild() {
            set = new HashSet<>(); sequence = new ArrayList<>(); unique = new ArrayList<>();
            if (prior != null) {
                for (int i = 0; i < index; i++) add(prior.sequence.get(i));
            }
            matching = false;
        }

        private void add(T value) {
            if (set.add(value) && unique != null) unique.add(value);
            if (sequence != null) {
                if (sequence.size() < MAX_ELEMENTS) sequence.add(value);
                else { sequence = null; unique = null; }
            }
        }

        void accept(T value) {
            if (verification != null) verification.add(value);
            if (value == null || value.getClass() != exactClass) safe = false;
            if (matching && safe && index < prior.sequence.size() && prior.sequence.get(index) == value) {
                index++; return;
            }
            if (matching) beginBuild();
            add(value); index++;
        }

        private HashSet<T> materialize() {
            if (matching) {
                if (index == prior.sequence.size()) {
                    set = new HashSet<>();
                    set.addAll(prior.unique);
                } else beginBuild();
            }
            return set;
        }

        Accumulator<T> merge(Accumulator<T> other) {
            HashSet<T> left = materialize(), right = other.materialize();
            if (left.size() < right.size()) { right.addAll(left); set = right; }
            else { left.addAll(right); set = left; }
            matching = false; combined = true;
            safe &= other.safe;
            sequence = null; unique = null;
            if (verification != null) verification.addAll(other.verification);
            return this;
        }

        Set<T> finish(Object owner, Scope scope) {
            boolean hit = matching && index == prior.sequence.size() && !combined;
            HashSet<T> result = materialize();
            if (VERIFY) {
                Set<T> reference = verification.stream().collect(java.util.stream.Collectors.toSet());
                List<T> actualOrder = new ArrayList<>(result), expectedOrder = new ArrayList<>(reference);
                boolean equivalent = actualOrder.size() == expectedOrder.size();
                for (int i = 0; equivalent && i < actualOrder.size(); i++) equivalent = actualOrder.get(i) == expectedOrder.get(i);
                if (!equivalent) { scope.mismatches++; throw new IllegalStateException("Multipart union identity/order mismatch"); }
            }
            if (combined) { scope.parallel++; scope.plans.remove(owner); }
            else if (!safe) { scope.unsafe++; scope.plans.remove(owner); }
            else if (sequence == null && !hit) { scope.oversized++; scope.plans.remove(owner); }
            else if (hit) scope.hits++;
            else {
                scope.misses++;
                if (scope.plans.containsKey(owner) || scope.plans.size() < MAX_MODELS) {
                    scope.plans.put(owner, new Plan<>(List.copyOf(sequence), List.copyOf(unique)));
                }
            }
            return result;
        }
    }
}
