package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.slf4j.Logger;

/** Low-overhead, opt-in diagnostic for the EntityRenderDispatcher reload slot. */
public final class EntityRendererReloadProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileEntityRendererReload", "false"));
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<Scope> ACTIVE_SCOPE = new ThreadLocal<>();
    private static final List<Row> ROWS = new ArrayList<>();
    private static final List<String> PROVIDERS = new ArrayList<>();
    private static boolean active;
    private static long reloadStartNanos;
    private static long reloadStartCpuNanos;
    private static String reloadThread;

    private EntityRendererReloadProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void beginReload() {
        if (!ENABLED || active) {
            return;
        }
        synchronized (ROWS) {
            ROWS.clear();
            PROVIDERS.clear();
        }
        active = true;
        reloadStartNanos = System.nanoTime();
        reloadStartCpuNanos = currentThreadCpuNanos();
        reloadThread = Thread.currentThread().getName();
    }

    public static <T> T time(String name, Supplier<T> body) {
        if (!ENABLED || !active) {
            return body.get();
        }
        Scope parent = ACTIVE_SCOPE.get();
        Scope scope = new Scope(name);
        ACTIVE_SCOPE.set(scope);
        long wallStart = System.nanoTime();
        long cpuStart = currentThreadCpuNanos();
        try {
            return body.get();
        } finally {
            long wall = System.nanoTime() - wallStart;
            long cpu = cpuDelta(cpuStart);
            synchronized (ROWS) {
                ROWS.add(new Row(name, wall, cpu, Thread.currentThread().getName(), new LinkedHashMap<>(scope.accesses)));
            }
            if (parent == null) {
                ACTIVE_SCOPE.remove();
            } else {
                ACTIVE_SCOPE.set(parent);
            }
        }
    }

    public static void timeVoid(String name, Runnable body) {
        time(name, () -> {
            body.run();
            return null;
        });
    }

    public static void invokeListener(EventListener listener, Event event) {
        if (!ENABLED || !active || !(event instanceof EntityRenderersEvent.AddLayers)) {
            listener.invoke(event);
            return;
        }
        String identity;
        try {
            identity = listener.toString();
        } catch (Throwable ignored) {
            identity = listener.getClass().getName();
        }
        final String stableIdentity = identity.replace('\n', ' ').replace('\r', ' ');
        timeVoid("subscriber:" + stableIdentity, () -> listener.invoke(event));
    }

    public static void contextAccess(String access) {
        if (!ENABLED || !active) {
            return;
        }
        Scope scope = ACTIVE_SCOPE.get();
        if (scope != null) {
            scope.accesses.merge(access, 1, Integer::sum);
        }
    }

    public static void snapshotProviders(Map<EntityType<?>, EntityRendererProvider<?>> entityProviders,
                                         Map<?, ? extends EntityRendererProvider<?>> playerProviders) {
        if (!ENABLED || !active) {
            return;
        }
        synchronized (ROWS) {
            PROVIDERS.clear();
            entityProviders.forEach((type, provider) -> PROVIDERS.add(
                    "kind=entity key=" + BuiltInRegistries.ENTITY_TYPE.getKey(type)
                            + " provider=" + provider.getClass().getName()
                            + " origin=" + codeOrigin(provider.getClass())));
            playerProviders.forEach((type, provider) -> PROVIDERS.add(
                    "kind=player key=" + String.valueOf(type)
                            + " provider=" + provider.getClass().getName()
                            + " origin=" + codeOrigin(provider.getClass())));
        }
    }

    public static void finishReload() {
        if (!ENABLED || !active) {
            return;
        }
        long totalWall = System.nanoTime() - reloadStartNanos;
        long totalCpu = cpuDelta(reloadStartCpuNanos);
        List<Row> rows;
        List<String> providers;
        synchronized (ROWS) {
            rows = List.copyOf(ROWS);
            providers = List.copyOf(PROVIDERS);
        }
        LOGGER.info(
                "BOOTOPTIM_ENTITY_RENDER_RELOAD summary total_ms={} cpu_ms={} thread={} providers={} rows={}",
                formatNanos(totalWall), formatCpuNanos(totalCpu), reloadThread, providers.size(), rows.size());
        for (String provider : providers) {
            LOGGER.info("BOOTOPTIM_ENTITY_RENDER_PROVIDER {}", provider);
        }
        for (Row row : rows) {
            LOGGER.info(
                    "BOOTOPTIM_ENTITY_RENDER_ROW name={} wall_ms={} cpu_ms={} thread={} accesses={}",
                    quote(row.name), formatNanos(row.wallNanos), formatCpuNanos(row.cpuNanos),
                    quote(row.thread), quote(formatAccesses(row.accesses)));
        }
        active = false;
        ACTIVE_SCOPE.remove();
    }

    private static String formatAccesses(Map<String, Integer> accesses) {
        if (accesses.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        accesses.forEach((key, value) -> {
            if (!out.isEmpty()) out.append(',');
            out.append(key).append(':').append(value);
        });
        return out.toString();
    }

    private static String codeOrigin(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            return location == null ? "unknown" : location.toString().replace(' ', '_');
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static long currentThreadCpuNanos() {
        try {
            if (THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() && THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                return THREAD_MX_BEAN.getCurrentThreadCpuTime();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
        }
        return -1L;
    }

    private static long cpuDelta(long start) {
        if (start < 0L) return -1L;
        long now = currentThreadCpuNanos();
        return now >= start ? now - start : -1L;
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String formatCpuNanos(long nanos) {
        return nanos < 0L ? "unavailable" : formatNanos(nanos);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static final class Scope {
        final String name;
        final Map<String, Integer> accesses = new LinkedHashMap<>();

        Scope(String name) {
            this.name = name;
        }
    }

    private record Row(String name, long wallNanos, long cpuNanos, String thread, Map<String, Integer> accesses) {}
}
