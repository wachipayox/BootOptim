package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only decomposition of the resource/model path exposed by the slow-laptop campaign.
 *
 * <p>Important measurement rule: task durations are deliberately reported as {@code task_sum}; they may
 * execute concurrently and are not recoverable wall time. Synchronous/future scopes are reported as
 * {@code wall_scope_sum}; multiple atlas/resource scopes can overlap and must not be added blindly either.
 */
public final class ResourcePipelineProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ResourcePipeline");
    private static final int TOP_LIMIT = 32;

    private static final ConcurrentHashMap<StatKey, Stat> PHASES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<StatKey, Stat> PACKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<StatKey, Stat> NAMESPACES = new ConcurrentHashMap<>();
    private static final Map<Object, String> ATLAS_LIST_IDS = Collections.synchronizedMap(new WeakHashMap<>());
    // Do not use withInitial here. FallbackResourceManager probes call currentContext() very often;
    // an absent context must stay allocation-free for unrelated resource traffic.
    private static final ThreadLocal<ArrayDeque<String>> CONTEXT = new ThreadLocal<>();
    private static final PriorityQueue<SlowResource> SLOW_RESOURCES =
            new PriorityQueue<>(Comparator.comparingLong(SlowResource::elapsedNanos));
    private static final Object SLOW_LOCK = new Object();
    private static final AtomicBoolean DUMPED = new AtomicBoolean();

    private ResourcePipelineProfiler() {
    }

    public static boolean enabled() {
        return StartupProfiler.isEnabled();
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void recordWallScope(String phase, long startedNanos, long items) {
        record("wall_scope_sum", phase, elapsed(startedNanos), items);
    }

    public static void recordTask(String phase, long startedNanos, long items) {
        record("task_sum", phase, elapsed(startedNanos), items);
    }

    public static void recordResource(
            String phase,
            ResourceLocation id,
            String packId,
            long startedNanos) {
        if (!enabled() || startedNanos <= 0L) {
            return;
        }
        long nanos = elapsed(startedNanos);
        record("task_sum", phase, nanos, 1L);

        String pack = packId == null ? "unknown" : packId;
        String namespace = id == null ? "unknown" : id.getNamespace();
        PACKS.computeIfAbsent(new StatKey(phase, pack), ignored -> new Stat()).add(nanos, 1L);
        NAMESPACES.computeIfAbsent(new StatKey(phase, namespace), ignored -> new Stat()).add(nanos, 1L);

        if (id != null) {
            SlowResource slow = new SlowResource(phase, id.toString(), pack, nanos);
            synchronized (SLOW_LOCK) {
                if (SLOW_RESOURCES.size() < TOP_LIMIT) {
                    SLOW_RESOURCES.add(slow);
                } else if (SLOW_RESOURCES.peek() != null && nanos > SLOW_RESOURCES.peek().elapsedNanos()) {
                    SLOW_RESOURCES.poll();
                    SLOW_RESOURCES.add(slow);
                }
            }
        }
    }

    public static void recordNamespace(
            String operation,
            String namespace,
            long startedNanos,
            long items) {
        if (!enabled() || startedNanos <= 0L) {
            return;
        }
        String context = currentContext();
        if (context == null) {
            return;
        }
        long nanos = elapsed(startedNanos);
        String detail = context + "/" + operation;
        NAMESPACES.computeIfAbsent(new StatKey(detail, namespace), ignored -> new Stat()).add(nanos, items);
    }

    public static void enterContext(String context) {
        if (!enabled()) {
            return;
        }
        ArrayDeque<String> stack = CONTEXT.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            CONTEXT.set(stack);
        }
        stack.push(context);
    }

    public static void exitContext() {
        if (!enabled()) {
            return;
        }
        ArrayDeque<String> stack = CONTEXT.get();
        if (stack == null) {
            return;
        }
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            CONTEXT.remove();
        }
    }

    public static String currentContext() {
        if (!enabled()) {
            return null;
        }
        ArrayDeque<String> stack = CONTEXT.get();
        return stack == null || stack.isEmpty() ? null : stack.peek();
    }

    public static void registerAtlasList(Object list, ResourceLocation atlasInfo) {
        if (!enabled() || list == null || atlasInfo == null) {
            return;
        }
        ATLAS_LIST_IDS.put(list, atlasInfo.toString());
    }

    public static String atlasListId(Object list) {
        String value = ATLAS_LIST_IDS.get(list);
        return value == null ? "unknown" : value;
    }

    public static void dump() {
        if (!enabled() || !DUMPED.compareAndSet(false, true)) {
            return;
        }

        probeModernFix();

        LOGGER.info(
                "BOOTOPTIM_RESOURCE_PIPELINE event=summary phase_keys={} pack_keys={} namespace_keys={} retained_slow_resources={}",
                PHASES.size(),
                PACKS.size(),
                NAMESPACES.size(),
                SLOW_RESOURCES.size());

        dumpStats("phase", PHASES, Integer.MAX_VALUE);
        dumpStats("pack", PACKS, TOP_LIMIT);
        dumpStats("namespace", NAMESPACES, TOP_LIMIT);

        List<SlowResource> slow;
        synchronized (SLOW_LOCK) {
            slow = new ArrayList<>(SLOW_RESOURCES);
        }
        slow.sort(Comparator.comparingLong(SlowResource::elapsedNanos).reversed());
        int rank = 1;
        for (SlowResource entry : slow) {
            LOGGER.info(
                    "BOOTOPTIM_RESOURCE_PIPELINE kind=slow_resource rank={} phase={} elapsed_ms={} id={} pack={}",
                    rank++,
                    entry.phase(),
                    formatMs(entry.elapsedNanos()),
                    entry.id(),
                    entry.pack());
        }
    }

    private static void record(String mode, String phase, long nanos, long items) {
        if (!enabled() || nanos < 0L) {
            return;
        }
        PHASES.computeIfAbsent(new StatKey(mode, phase), ignored -> new Stat()).add(nanos, items);
    }

    private static void dumpStats(String kind, ConcurrentHashMap<StatKey, Stat> source, int limit) {
        List<Map.Entry<StatKey, Stat>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<StatKey, Stat> e) -> e.getValue().totalNanos()).reversed());
        int emitted = 0;
        for (Map.Entry<StatKey, Stat> entry : entries) {
            if (emitted++ >= limit) {
                break;
            }
            Stat stat = entry.getValue();
            StatKey key = entry.getKey();
            LOGGER.info(
                    "BOOTOPTIM_RESOURCE_PIPELINE kind={} group={} detail={} calls={} items={} total_ms={} avg_ms={} max_ms={}",
                    kind,
                    key.group(),
                    key.detail(),
                    stat.count(),
                    stat.items(),
                    formatMs(stat.totalNanos()),
                    formatMs(stat.averageNanos()),
                    formatMs(stat.maxNanos()));
        }
    }

    /**
     * Diagnostic-only ModernFix 5.27.14 probe.
     *
     * <p>The exact 1.21.1 release exposes a boolean {@code Option} directly through
     * {@code ModernFixEarlyConfig#getEffectiveOptionForMixin(String)}. It does not have the later
     * {@code Option#asBoolean()/getValue()} API. Selection is also separate from the permanent per-mixin
     * compatibility gate, so both layers are reported. Finally, source-pinned structural markers on the
     * already-transformed target classes distinguish "selected" from "actually merged" without adding a
     * compile/runtime dependency on ModernFix.
     */
    private static void probeModernFix() {
        try {
            ClassLoader loader = ResourcePipelineProfiler.class.getClassLoader();
            Class<?> pluginClass = Class.forName("org.embeddedt.modernfix.core.ModernFixMixinPlugin", false, loader);
            Field instanceField = pluginClass.getField("instance");
            Object plugin = instanceField.get(null);
            if (plugin == null) {
                LOGGER.info("BOOTOPTIM_RESOURCE_CONFIG mod=modernfix status=present plugin=not_initialized");
                return;
            }

            String implementationVersion = pluginClass.getPackage().getImplementationVersion();
            LOGGER.info(
                    "BOOTOPTIM_RESOURCE_CONFIG mod=modernfix status=present implementation_version={}",
                    implementationVersion == null ? "unknown" : implementationVersion);

            Field configField = pluginClass.getField("config");
            Object config = configField.get(plugin);
            Method effective = config.getClass().getMethod("getEffectiveOptionForMixin", String.class);
            Method permanentlyDisabledMethod = config.getClass().getMethod("getPermanentlyDisabledMixins");
            Object disabledValue = permanentlyDisabledMethod.invoke(config);
            Map<?, ?> permanentlyDisabled = disabledValue instanceof Map<?, ?> map ? map : Map.of();

            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.dynamic_resources",
                    "perf.dynamic_resources.ModelManagerMixin");
            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.dynamic_resources",
                    "perf.dynamic_resources.ModelBakeryMixin");
            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.dynamic_resources",
                    "perf.dynamic_resources.BlockStateModelLoaderMixin");
            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.resourcepacks",
                    "perf.resourcepacks.FilePackResourcesMixin");
            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.resourcepacks",
                    "perf.resourcepacks.PathPackResourcesMixin");
            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.faster_texture_stitching",
                    "perf.faster_texture_stitching.StitcherMixin");
            probeModernFixOption(
                    loader,
                    config,
                    effective,
                    permanentlyDisabled,
                    "mixin.perf.deduplicate_wall_shapes",
                    "perf.deduplicate_wall_shapes.WallBlockMixin");

            try {
                Class<?> earlyConfigClass = Class.forName(
                        "org.embeddedt.modernfix.core.config.ModernFixEarlyConfig",
                        false,
                        loader);
                Object value = earlyConfigClass.getField("ACTIVE_FEATURE_LEVEL").get(null);
                LOGGER.info("BOOTOPTIM_RESOURCE_CONFIG mod=modernfix option=stability_level effective={}", value);
            } catch (ReflectiveOperationException failure) {
                LOGGER.info(
                        "BOOTOPTIM_RESOURCE_CONFIG mod=modernfix option=stability_level effective=probe_failed reason={}",
                        failure.getClass().getName());
            }
        } catch (ClassNotFoundException absent) {
            LOGGER.info("BOOTOPTIM_RESOURCE_CONFIG mod=modernfix status=absent");
        } catch (Throwable failure) {
            LOGGER.info(
                    "BOOTOPTIM_RESOURCE_CONFIG mod=modernfix status=probe_failed reason={}",
                    failure.getClass().getName());
        }
    }

    private static void probeModernFixOption(
            ClassLoader loader,
            Object config,
            Method effective,
            Map<?, ?> permanentlyDisabled,
            String optionName,
            String mixinPath) {
        try {
            Object option = effective.invoke(config, mixinPath);
            if (option == null) {
                LOGGER.info(
                        "BOOTOPTIM_RESOURCE_CONFIG mod=modernfix option={} mixin={} effective=unmatched applied_structural={}",
                        optionName,
                        mixinPath,
                        probeModernFixStructuralMarker(loader, mixinPath));
                return;
            }

            Object enabledValue = option.getClass().getMethod("isEnabled").invoke(option);
            boolean enabled = Boolean.TRUE.equals(enabledValue);
            Object permanentReasonValue = permanentlyDisabled.get(mixinPath);
            String permanentReason = permanentReasonValue == null ? "none" : String.valueOf(permanentReasonValue);
            boolean selected = enabled && permanentReasonValue == null;
            String rule = optionalString(option, "getName", "unknown");
            String userDefined = optionalString(option, "isUserDefined", "unknown");
            String modDefined = optionalString(option, "isModDefined", "unknown");
            String definingMods = optionalString(option, "getDefiningMods", "unknown");
            LOGGER.info(
                    "BOOTOPTIM_RESOURCE_CONFIG mod=modernfix option={} mixin={} effective={} controlling_rule={} user_defined={} mod_defined={} defining_mods={} permanent_disable={} selected_by_modernfix={} applied_structural={}",
                    optionName,
                    mixinPath,
                    enabled,
                    rule,
                    userDefined,
                    modDefined,
                    definingMods,
                    permanentReason,
                    selected,
                    probeModernFixStructuralMarker(loader, mixinPath));
        } catch (Throwable failure) {
            LOGGER.info(
                    "BOOTOPTIM_RESOURCE_CONFIG mod=modernfix option={} mixin={} effective=probe_failed reason={}",
                    optionName,
                    mixinPath,
                    failure.getClass().getName());
        }
    }

    private static String probeModernFixStructuralMarker(ClassLoader loader, String mixinPath) {
        try {
            return switch (mixinPath) {
                case "perf.dynamic_resources.ModelManagerMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.duck.IExtendedModelManager", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.client.resources.model.ModelManager",
                                        false,
                                        loader)));
                case "perf.dynamic_resources.ModelBakeryMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.duck.IExtendedModelBakery", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.client.resources.model.ModelBakery",
                                        false,
                                        loader)));
                case "perf.dynamic_resources.BlockStateModelLoaderMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.duck.IBlockStateModelLoader", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.client.resources.model.BlockStateModelLoader",
                                        false,
                                        loader)));
                case "perf.resourcepacks.FilePackResourcesMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.server.packs.FilePackResources", false, loader),
                        "mf$packIndex"));
                case "perf.resourcepacks.PathPackResourcesMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.server.packs.PathPackResources", false, loader),
                        "cacheEngine"));
                case "perf.faster_texture_stitching.StitcherMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.client.renderer.texture.Stitcher", false, loader),
                        "loadableSpriteInfos"));
                case "perf.deduplicate_wall_shapes.WallBlockMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.world.level.block.WallBlock", false, loader),
                        "CACHE_BY_SHAPE_VALS"));
                default -> "unknown_marker";
            };
        } catch (Throwable failure) {
            return "probe_failed:" + failure.getClass().getSimpleName();
        }
    }

    private static boolean hasDeclaredField(Class<?> target, String fieldName) {
        try {
            target.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }

    private static String optionalString(Object target, String methodName, String fallback) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static long elapsed(long startedNanos) {
        return startedNanos <= 0L ? -1L : Math.max(0L, System.nanoTime() - startedNanos);
    }

    private static String formatMs(long nanos) {
        if (nanos < 0L) {
            return "-1";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record StatKey(String group, String detail) {
    }

    private record SlowResource(String phase, String id, String pack, long elapsedNanos) {
    }

    private static final class Stat {
        private final LongAdder count = new LongAdder();
        private final LongAdder items = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void add(long nanos, long itemCount) {
            count.increment();
            if (itemCount > 0L) {
                items.add(itemCount);
            }
            totalNanos.add(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        long count() {
            return count.sum();
        }

        long items() {
            return items.sum();
        }

        long totalNanos() {
            return totalNanos.sum();
        }

        long maxNanos() {
            return maxNanos.get();
        }

        long averageNanos() {
            long calls = count();
            return calls == 0L ? 0L : totalNanos() / calls;
        }
    }
}
