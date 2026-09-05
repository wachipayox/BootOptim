package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Diagnostic-only aggregation for shader capability probes, Voxy config saves and the broad
 * FancyMenu preload phase. This class observes stock calls and never replaces GL, resource, or IO
 * behavior.
 */
public final class ShaderVoxyVarianceDiagnostic {
    public static final String PROPERTY = "boot_optim.shaderVoxyVarianceDiagnostic";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final Pattern GLSL_VERSION = Pattern.compile("(?i)GLSL\\s+(\\d+(?:\\.\\d+)?)");
    private static final long CALLBACK_GROUP_GAP_NANOS = 5_000_000_000L;
    private static final Object LOCK = new Object();

    private static final ThreadLocal<ArrayDeque<SpanStart>> SHADER_SPANS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<SpanStart>> VOXY_SAVE_SPANS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<SpanStart>> FANCYMENU_SPANS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final Map<String, ProbeStats> SHADER_PROBES = new LinkedHashMap<>();
    private static final Map<String, Long> SHADER_DETAILS = new LinkedHashMap<>();
    private static final Map<String, Long> CALLBACK_VERSIONS = new LinkedHashMap<>();
    private static final Map<String, Long> CALLBACK_CALLERS = new LinkedHashMap<>();
    private static final Map<String, Long> VOXY_SAVE_CALLERS = new LinkedHashMap<>();

    private static Boolean cpuTimeAvailable;
    private static boolean reported;
    private static long shaderNested;
    private static long callbackCount;
    private static long callbackGroups;
    private static long callbackComputeMentions;
    private static long callbackRenderThreadCount;
    private static long callbackOtherThreadCount;
    private static long callbackLastNanos;

    private static long voxySaveCalls;
    private static long voxySaveCompleted;
    private static long voxySaveWallNanos;
    private static long voxySaveCpuNanos;
    private static long voxySaveCpuSamples;
    private static long voxySaveRenderThreadCalls;
    private static long voxySaveOtherThreadCalls;
    private static int activeVoxySaves;
    private static int maxConcurrentVoxySaves;
    private static long voxySaveNested;

    private static long fancyMenuCalls;
    private static long fancyMenuWallNanos;
    private static long fancyMenuCpuNanos;
    private static long fancyMenuCpuSamples;
    private static long fancyMenuNested;

    private ShaderVoxyVarianceDiagnostic() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static String versionFromSource(String source) {
        if (source == null) {
            return "unknown";
        }
        Matcher matcher = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)").matcher(source);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    public static void beginShaderProbe(String owner, String detail) {
        if (!isEnabled()) {
            return;
        }
        ArrayDeque<SpanStart> stack = SHADER_SPANS.get();
        if (!stack.isEmpty()) {
            synchronized (LOCK) {
                shaderNested++;
            }
        }
        stack.push(new SpanStart(System.nanoTime(), currentThreadCpuTime(), owner, detail));
    }

    public static void endShaderProbe(String owner, String detail, boolean success) {
        if (!isEnabled()) {
            return;
        }
        ArrayDeque<SpanStart> stack = SHADER_SPANS.get();
        if (stack.isEmpty()) {
            synchronized (LOCK) {
                shaderNested++;
            }
            return;
        }

        SpanStart start = stack.pop();
        long wallNanos = Math.max(0L, System.nanoTime() - start.wallNanos());
        long cpuEnd = currentThreadCpuTime();
        long cpuNanos = start.cpuNanos() >= 0L && cpuEnd >= start.cpuNanos()
                ? cpuEnd - start.cpuNanos()
                : -1L;
        String resolvedOwner = start.owner() == null ? owner : start.owner();
        String resolvedDetail = start.detail() == null ? detail : start.detail();

        synchronized (LOCK) {
            ProbeStats stats = SHADER_PROBES.computeIfAbsent(resolvedOwner, ignored -> new ProbeStats());
            stats.calls++;
            if (success) {
                stats.successes++;
            } else {
                stats.failures++;
            }
            stats.wallNanos += wallNanos;
            if (cpuNanos >= 0L) {
                stats.cpuNanos += cpuNanos;
                stats.cpuSamples++;
            }
            if (isRenderThread()) {
                stats.renderThreadCalls++;
            } else {
                stats.otherThreadCalls++;
            }
            SHADER_DETAILS.merge(resolvedOwner + ":" + resolvedDetail, 1L, Long::sum);
        }
    }

    public static void recordShaderCompilerDebug(String message) {
        if (!isEnabled()) {
            return;
        }
        long now = System.nanoTime();
        String text = message == null ? "" : message;
        String caller = callerFingerprint();
        Matcher matcher = GLSL_VERSION.matcher(text);
        String version = matcher.find() ? matcher.group(1) : "unknown";

        synchronized (LOCK) {
            callbackCount++;
            if (callbackLastNanos == 0L || now - callbackLastNanos > CALLBACK_GROUP_GAP_NANOS) {
                callbackGroups++;
            }
            callbackLastNanos = now;
            if (text.toLowerCase(Locale.ROOT).contains("compute")) {
                callbackComputeMentions++;
            }
            if (isRenderThread()) {
                callbackRenderThreadCount++;
            } else {
                callbackOtherThreadCount++;
            }
            CALLBACK_VERSIONS.merge(version, 1L, Long::sum);
            CALLBACK_CALLERS.merge(caller, 1L, Long::sum);
        }
    }

    public static void beginVoxySave() {
        if (!isEnabled()) {
            return;
        }
        ArrayDeque<SpanStart> stack = VOXY_SAVE_SPANS.get();
        if (!stack.isEmpty()) {
            synchronized (LOCK) {
                voxySaveNested++;
            }
        }
        SpanStart start = new SpanStart(System.nanoTime(), currentThreadCpuTime(), callerFingerprint(), null);
        stack.push(start);
        synchronized (LOCK) {
            voxySaveCalls++;
            activeVoxySaves++;
            maxConcurrentVoxySaves = Math.max(maxConcurrentVoxySaves, activeVoxySaves);
            if (isRenderThread()) {
                voxySaveRenderThreadCalls++;
            } else {
                voxySaveOtherThreadCalls++;
            }
            VOXY_SAVE_CALLERS.merge(start.owner(), 1L, Long::sum);
        }
    }

    public static void endVoxySave() {
        if (!isEnabled()) {
            return;
        }
        ArrayDeque<SpanStart> stack = VOXY_SAVE_SPANS.get();
        if (stack.isEmpty()) {
            synchronized (LOCK) {
                voxySaveNested++;
            }
            return;
        }
        SpanStart start = stack.pop();
        long wallNanos = Math.max(0L, System.nanoTime() - start.wallNanos());
        long cpuEnd = currentThreadCpuTime();
        long cpuNanos = start.cpuNanos() >= 0L && cpuEnd >= start.cpuNanos()
                ? cpuEnd - start.cpuNanos()
                : -1L;
        synchronized (LOCK) {
            voxySaveCompleted++;
            activeVoxySaves = Math.max(0, activeVoxySaves - 1);
            voxySaveWallNanos += wallNanos;
            if (cpuNanos >= 0L) {
                voxySaveCpuNanos += cpuNanos;
                voxySaveCpuSamples++;
            }
        }
    }

    public static void beginFancyMenuPreload() {
        if (!isEnabled()) {
            return;
        }
        ArrayDeque<SpanStart> stack = FANCYMENU_SPANS.get();
        if (!stack.isEmpty()) {
            synchronized (LOCK) {
                fancyMenuNested++;
            }
        }
        stack.push(new SpanStart(System.nanoTime(), currentThreadCpuTime(), Thread.currentThread().getName(), null));
    }

    public static void endFancyMenuPreload() {
        if (!isEnabled()) {
            return;
        }
        ArrayDeque<SpanStart> stack = FANCYMENU_SPANS.get();
        if (stack.isEmpty()) {
            synchronized (LOCK) {
                fancyMenuNested++;
            }
            return;
        }
        SpanStart start = stack.pop();
        long wallNanos = Math.max(0L, System.nanoTime() - start.wallNanos());
        long cpuEnd = currentThreadCpuTime();
        long cpuNanos = start.cpuNanos() >= 0L && cpuEnd >= start.cpuNanos()
                ? cpuEnd - start.cpuNanos()
                : -1L;
        synchronized (LOCK) {
            fancyMenuCalls++;
            fancyMenuWallNanos += wallNanos;
            if (cpuNanos >= 0L) {
                fancyMenuCpuNanos += cpuNanos;
                fancyMenuCpuSamples++;
            }
        }
    }

    public static void reportAtTitle() {
        if (!isEnabled()) {
            return;
        }
        synchronized (LOCK) {
            if (reported) {
                return;
            }
            reported = true;

            long shaderCalls = SHADER_PROBES.values().stream().mapToLong(stats -> stats.calls).sum();
            long shaderFailures = SHADER_PROBES.values().stream().mapToLong(stats -> stats.failures).sum();
            long shaderWallNanos = SHADER_PROBES.values().stream().mapToLong(stats -> stats.wallNanos).sum();
            long shaderCpuNanos = SHADER_PROBES.values().stream().mapToLong(stats -> stats.cpuNanos).sum();
            long shaderCpuSamples = SHADER_PROBES.values().stream().mapToLong(stats -> stats.cpuSamples).sum();

            LOGGER.info(
                    "BOOTOPTIM_SHADER_VARIANCE status={} probe_calls={} probe_failures={} probe_wall_ms={} probe_cpu_ms={} nested={} callback_count={} callback_groups={} callback_compute_mentions={} callback_render_thread={} callback_other_thread={} probe_details={} callback_versions={} callback_callers={}",
                    shaderCalls > 0L || callbackCount > 0L ? "ok" : "zero_coverage",
                    shaderCalls,
                    shaderFailures,
                    formatMillis(shaderWallNanos),
                    formatCpuMillis(shaderCpuNanos, shaderCpuSamples),
                    shaderNested,
                    callbackCount,
                    callbackGroups,
                    callbackComputeMentions,
                    callbackRenderThreadCount,
                    callbackOtherThreadCount,
                    formatMap(SHADER_DETAILS),
                    formatMap(CALLBACK_VERSIONS),
                    formatMap(CALLBACK_CALLERS));

            LOGGER.info(
                    "BOOTOPTIM_VOXY_SAVE_VARIANCE status={} calls={} completed={} active_at_title={} max_concurrent={} wall_ms={} cpu_ms={} render_thread_calls={} other_thread_calls={} nested={} callers={}",
                    voxySaveCalls > 0L ? "ok" : "zero_coverage",
                    voxySaveCalls,
                    voxySaveCompleted,
                    activeVoxySaves,
                    maxConcurrentVoxySaves,
                    formatMillis(voxySaveWallNanos),
                    formatCpuMillis(voxySaveCpuNanos, voxySaveCpuSamples),
                    voxySaveRenderThreadCalls,
                    voxySaveOtherThreadCalls,
                    voxySaveNested,
                    formatMap(VOXY_SAVE_CALLERS));

            LOGGER.info(
                    "BOOTOPTIM_FANCYMENU_PHASE_VARIANCE status={} calls={} wall_ms={} cpu_ms={} nested={}",
                    fancyMenuCalls > 0L ? "ok" : "zero_coverage",
                    fancyMenuCalls,
                    formatMillis(fancyMenuWallNanos),
                    formatCpuMillis(fancyMenuCpuNanos, fancyMenuCpuSamples),
                    fancyMenuNested);
        }
    }

    private static long currentThreadCpuTime() {
        if (!ensureCpuTimeAvailable()) {
            return -1L;
        }
        try {
            return THREAD_MX_BEAN.getCurrentThreadCpuTime();
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return -1L;
        }
    }

    private static boolean ensureCpuTimeAvailable() {
        synchronized (LOCK) {
            if (cpuTimeAvailable != null) {
                return cpuTimeAvailable;
            }
            if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()) {
                cpuTimeAvailable = false;
                return false;
            }
            try {
                if (!THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                    THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
                }
                cpuTimeAvailable = THREAD_MX_BEAN.isThreadCpuTimeEnabled();
            } catch (UnsupportedOperationException | SecurityException ignored) {
                cpuTimeAvailable = false;
            }
            return cpuTimeAvailable;
        }
    }

    private static boolean isRenderThread() {
        return "Render thread".equals(Thread.currentThread().getName());
    }

    private static String callerFingerprint() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("dev.engine_room.flywheel.")) {
                return "flywheel:" + className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
            if (className.startsWith("me.cortex.voxy.")) {
                return "voxy:" + className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
            if (className.startsWith("foundry.veil.")) {
                return "veil:" + className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
            if (className.startsWith("net.irisshaders.iris.")) {
                return "iris:" + className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
            if (className.startsWith("com.github.argon4w.acceleratedrendering.")) {
                return "acceleratedrendering:" + className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
        }
        return "unknown";
    }

    private static String formatMap(Map<String, ? extends Number> values) {
        if (values.isEmpty()) {
            return "none";
        }
        return values.entrySet().stream()
                .map(entry -> sanitize(entry.getKey()) + ':' + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replace(' ', '_').replace(',', ';');
    }

    private static String formatCpuMillis(long nanos, long samples) {
        return samples > 0L ? formatMillis(nanos) : "na";
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record SpanStart(long wallNanos, long cpuNanos, String owner, String detail) {
    }

    private static final class ProbeStats {
        long calls;
        long successes;
        long failures;
        long wallNanos;
        long cpuNanos;
        long cpuSamples;
        long renderThreadCalls;
        long otherThreadCalls;
    }
}
