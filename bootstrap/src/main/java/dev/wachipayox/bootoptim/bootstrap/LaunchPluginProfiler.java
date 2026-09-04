package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Diagnostic aggregation for individual ModLauncher launch-plugin callbacks. */
final class LaunchPluginProfiler {
    private static final int TOP_OPERATIONS = 40;
    private static final int TOP_RESULTS = 30;
    private static final int TOP_RESULT_GROUPS = 40;
    private static final int TOP_INVOCATIONS = 60;
    private static final Map<String, Stats> BY_OPERATION = new ConcurrentHashMap<>();
    private static final Map<String, Stats> BY_PROCESS_RESULT = new ConcurrentHashMap<>();
    private static final Map<String, Stats> MIXIN_BY_RESULT_GROUP = new ConcurrentHashMap<>();
    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder CALLBACK_NANOS = new LongAdder();
    private static final LongAdder PROFILER_NANOS = new LongAdder();
    private static final Object TOP_LOCK = new Object();
    private static final PriorityQueue<Invocation> SLOWEST =
            new PriorityQueue<>(Comparator.comparingLong(Invocation::nanos));
    private static volatile boolean installed;

    private LaunchPluginProfiler() {
    }

    static void installed(List<String> pluginNames) {
        installed = true;
        emit("BOOTOPTIM_LAUNCH_PLUGIN_PROFILE",
                "status=enabled plugins=" + String.join(",", pluginNames));
    }

    static void record(
            String plugin,
            String operation,
            String className,
            String reason,
            String phase,
            long callbackNanos,
            int transformDepth) {
        recordInternal(plugin, operation, className, reason, phase, callbackNanos, transformDepth, null);
    }

    static void recordProcessFlags(
            String plugin,
            String className,
            String reason,
            String phase,
            long callbackNanos,
            int transformDepth,
            int resultFlags) {
        recordInternal(plugin, "process_flags", className, reason, phase, callbackNanos, transformDepth, resultFlags);
    }

    private static void recordInternal(
            String plugin,
            String operation,
            String className,
            String reason,
            String phase,
            long callbackNanos,
            int transformDepth,
            Integer resultFlags) {
        long profilerStart = System.nanoTime();
        try {
            CALLS.increment();
            CALLBACK_NANOS.add(callbackNanos);
            String aggregateKey = plugin + ":" + operation + (phase == null ? "" : ":" + phase);
            BY_OPERATION.computeIfAbsent(aggregateKey, ignored -> new Stats())
                    .add(callbackNanos, transformDepth);

            String result = null;
            if (resultFlags != null) {
                result = resultLabel(resultFlags);
                String resultKey = plugin + ":" + (phase == null ? "<none>" : phase) + ":" + result;
                BY_PROCESS_RESULT.computeIfAbsent(resultKey, ignored -> new Stats())
                        .add(callbackNanos, transformDepth);
                if ("mixin".equals(plugin) && className != null) {
                    String groupKey = result + ":" + group(className);
                    MIXIN_BY_RESULT_GROUP.computeIfAbsent(groupKey, ignored -> new Stats())
                            .add(callbackNanos, transformDepth);
                }
            }

            if (className != null || callbackNanos >= 1_000_000L) {
                offerSlow(new Invocation(
                        plugin,
                        operation,
                        phase == null ? "<none>" : phase,
                        className == null ? "<lifecycle>" : className,
                        reason == null ? "<null>" : reason,
                        result == null ? "<none>" : result,
                        callbackNanos,
                        transformDepth));
            }
        } finally {
            PROFILER_NANOS.add(System.nanoTime() - profilerStart);
        }
    }

    private static String resultLabel(int flags) {
        if (flags == ILaunchPluginService.ComputeFlags.NO_REWRITE) {
            return "no_rewrite";
        }
        if (flags == ILaunchPluginService.ComputeFlags.SIMPLE_REWRITE) {
            return "simple_rewrite";
        }
        if (flags == ILaunchPluginService.ComputeFlags.COMPUTE_MAXS) {
            return "compute_maxs";
        }
        if (flags == ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES) {
            return "compute_frames";
        }
        return String.format(Locale.ROOT, "rewrite_0x%x", flags);
    }

    private static String group(String className) {
        String[] parts = className.split("\\.");
        if (parts.length <= 3) {
            return className;
        }
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private static void offerSlow(Invocation invocation) {
        synchronized (TOP_LOCK) {
            if (SLOWEST.size() < TOP_INVOCATIONS) {
                SLOWEST.add(invocation);
                return;
            }
            Invocation smallest = SLOWEST.peek();
            if (smallest != null && invocation.nanos > smallest.nanos) {
                SLOWEST.poll();
                SLOWEST.add(invocation);
            }
        }
    }

    static void report(String reason) {
        if (!installed) {
            return;
        }

        emit("BOOTOPTIM_LAUNCH_PLUGIN_PROFILE", String.format(
                Locale.ROOT,
                "summary=%s calls=%d callback_inclusive_ms=%.3f profiler_overhead_ms=%.3f",
                reason,
                CALLS.sum(),
                CALLBACK_NANOS.sum() / 1_000_000.0,
                PROFILER_NANOS.sum() / 1_000_000.0));

        reportStats("operation", BY_OPERATION, TOP_OPERATIONS);
        reportStats("process_result", BY_PROCESS_RESULT, TOP_RESULTS);
        reportStats("mixin_result_group", MIXIN_BY_RESULT_GROUP, TOP_RESULT_GROUPS);

        List<Invocation> invocations;
        synchronized (TOP_LOCK) {
            invocations = new ArrayList<>(SLOWEST);
        }
        invocations.sort(Comparator.comparingLong(Invocation::nanos).reversed());
        for (int i = 0; i < invocations.size(); i++) {
            Invocation invocation = invocations.get(i);
            emit("BOOTOPTIM_LAUNCH_PLUGIN_PROFILE_TOP", String.format(
                    Locale.ROOT,
                    "dimension=invocation rank=%d plugin=%s operation=%s phase=%s class=%s reason=%s result=%s transform_depth=%d total_ms=%.3f",
                    i + 1,
                    invocation.plugin,
                    invocation.operation,
                    invocation.phase,
                    invocation.className,
                    invocation.reason,
                    invocation.result,
                    invocation.transformDepth,
                    invocation.nanos / 1_000_000.0));
        }
    }

    private static void reportStats(String dimension, Map<String, Stats> source, int limit) {
        List<Map.Entry<String, Stats>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Stats> entry) -> entry.getValue().nanos())
                .reversed());
        int count = Math.min(limit, entries.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<String, Stats> entry = entries.get(i);
            Stats stats = entry.getValue();
            long calls = stats.calls();
            emit("BOOTOPTIM_LAUNCH_PLUGIN_PROFILE_TOP", String.format(
                    Locale.ROOT,
                    "dimension=%s rank=%d key=%s calls=%d transform_depth0_calls=%d nested_transform_calls=%d total_ms=%.3f depth0_ms=%.3f nested_depth_ms=%.3f avg_us=%.3f max_us=%.3f",
                    dimension,
                    i + 1,
                    entry.getKey(),
                    calls,
                    stats.depthZeroCalls(),
                    stats.nestedDepthCalls(),
                    stats.nanos() / 1_000_000.0,
                    stats.depthZeroNanos() / 1_000_000.0,
                    stats.nestedDepthNanos() / 1_000_000.0,
                    calls == 0 ? 0.0 : stats.nanos() / 1_000.0 / calls,
                    stats.maxNanos() / 1_000.0));
        }
    }

    private static void emit(String category, String payload) {
        System.out.println(category + " " + payload);
        StartupDiagnostics.event(category, payload);
    }

    private static final class Stats {
        private final LongAdder calls = new LongAdder();
        private final LongAdder depthZeroCalls = new LongAdder();
        private final LongAdder nestedDepthCalls = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final LongAdder depthZeroNanos = new LongAdder();
        private final LongAdder nestedDepthNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void add(long elapsed, int transformDepth) {
            calls.increment();
            nanos.add(elapsed);
            if (transformDepth == 0) {
                depthZeroCalls.increment();
                depthZeroNanos.add(elapsed);
            } else {
                nestedDepthCalls.increment();
                nestedDepthNanos.add(elapsed);
            }
            maxNanos.accumulateAndGet(elapsed, Math::max);
        }

        long calls() { return calls.sum(); }
        long depthZeroCalls() { return depthZeroCalls.sum(); }
        long nestedDepthCalls() { return nestedDepthCalls.sum(); }
        long nanos() { return nanos.sum(); }
        long depthZeroNanos() { return depthZeroNanos.sum(); }
        long nestedDepthNanos() { return nestedDepthNanos.sum(); }
        long maxNanos() { return maxNanos.get(); }
    }

    private record Invocation(
            String plugin,
            String operation,
            String phase,
            String className,
            String reason,
            String result,
            long nanos,
            int transformDepth) {
    }
}
