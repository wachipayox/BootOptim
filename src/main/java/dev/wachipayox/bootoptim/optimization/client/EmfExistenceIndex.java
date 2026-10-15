package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.compat.client.CompatLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Answers Entity Model Features' "does this {@code .jem}/{@code .properties} exist" probes from
 * the complete directory listing EMF itself already took, instead of asking every resource pack
 * again for each absent candidate.
 *
 * <p>EMF lists {@code emf/cem} and {@code optifine/cem} once per reload and then still calls
 * {@code ResourceManager.getResource} for every candidate that is not in the listing. Those
 * absent probes walk the whole pack stack. When the listing is provably complete (unfiltered, and
 * every pack is a static stock-behaving pack), a path below the listed prefix that is not in it
 * cannot exist, so the probe can be answered without touching the packs.</p>
 *
 * <p>Nothing here is tied to an EMF version. The mixin finds its targets by shape; if EMF changes
 * them the post-apply check logs {@code [WBOMBO-COMPAT] FALLBACK}. At run time a sampled
 * differential guard repeats the stock lookup and disables the mechanism for good on the first
 * disagreement.</p>
 */
public final class EmfExistenceIndex {
    public static final String MECHANISM = "emf-existence-index";
    public static final String MOD_ID = "entity_model_features";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENABLE_PROPERTY = "boot_optim.emfExistenceIndex";
    private static final int MAX_LISTED = 32768;
    /** Verify the first probes against stock, then one in {@code VERIFY_EVERY}. */
    private static final int VERIFY_FIRST = 64;
    private static final int VERIFY_EVERY = 64;
    private static final int REPORT_EVERY = 1000;

    private static final Object LOCK = new Object();
    private static final Map<ResourceManager, Snapshot> SNAPSHOTS = new WeakHashMap<>();
    private static long generation;
    private static boolean disabled;
    private static long avoided;
    private static long verified;
    private static long verifiedNanos;

    private static final class Snapshot {
        final boolean supported;
        final Map<String, Set<ResourceLocation>> prefixes = new HashMap<>();

        Snapshot(boolean supported) {
            this.supported = supported;
        }
    }

    private EmfExistenceIndex() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    /** Value to capture before the stock enumeration; a later {@link #clear()} invalidates it. */
    public static long generation() {
        synchronized (LOCK) {
            return generation;
        }
    }

    /** EMF reload boundary: drop every snapshot and report the finished generation. */
    public static void clear() {
        synchronized (LOCK) {
            report("reload");
            SNAPSHOTS.clear();
            avoided = 0;
            verified = 0;
            verifiedNanos = 0;
            generation++;
        }
    }

    /** Records a stock listing if, and only if, it is provably the complete content of the prefix. */
    public static void record(ResourceManager manager, String prefix, Predicate<ResourceLocation> filter,
                              Set<ResourceLocation> listed, long originGeneration) {
        if (!enabled() || prefix == null || listed.size() > MAX_LISTED || !acceptsEverything(prefix, filter)) {
            return;
        }
        synchronized (LOCK) {
            if (disabled || originGeneration != generation) {
                return;
            }
            Snapshot snapshot = SNAPSHOTS.get(manager);
            if (snapshot == null) {
                snapshot = inventory(manager);
                SNAPSHOTS.put(manager, snapshot);
            }
            if (snapshot.supported) {
                snapshot.prefixes.put(prefix, Set.copyOf(listed));
            }
        }
    }

    /**
     * True when {@code resource} is below a completely listed prefix and not in its listing.
     * The caller must then use {@link #shouldVerify()} to decide whether to re-check stock.
     */
    public static boolean knownAbsent(ResourceManager manager, ResourceLocation resource) {
        String path = resource.getPath();
        if (!(path.endsWith(".jem") || path.endsWith(".properties"))) {
            return false;
        }
        synchronized (LOCK) {
            if (disabled) {
                return false;
            }
            Snapshot snapshot = SNAPSHOTS.get(manager);
            if (snapshot == null || !snapshot.supported) {
                return false;
            }
            for (Map.Entry<String, Set<ResourceLocation>> entry : snapshot.prefixes.entrySet()) {
                if (path.startsWith(entry.getKey() + "/") && !entry.getValue().contains(resource)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Whether this absent answer should be re-checked against the stock lookup. */
    public static boolean shouldVerify() {
        synchronized (LOCK) {
            long ordinal = avoided + verified + 1;
            return ordinal <= VERIFY_FIRST || ordinal % VERIFY_EVERY == 0;
        }
    }

    /** Stock lookup agreed that the resource is absent; {@code nanos} is what that probe cost. */
    public static void confirmed(long nanos) {
        synchronized (LOCK) {
            verified++;
            verifiedNanos += nanos;
            milestone();
        }
    }

    /** An answer served from the index without a stock lookup. */
    public static void served() {
        synchronized (LOCK) {
            avoided++;
            milestone();
        }
    }

    /** Stock found a resource the index called absent: disable for the rest of the session. */
    public static void mismatch(ResourceLocation resource) {
        synchronized (LOCK) {
            if (disabled) {
                return;
            }
            disabled = true;
            SNAPSHOTS.clear();
        }
        CompatLog.fallback(MECHANISM, MOD_ID, "differential_mismatch resource=" + resource);
    }

    private static void milestone() {
        long total = avoided + verified;
        if (total == 1) {
            CompatLog.active(MECHANISM, MOD_ID);
        }
        if (total % REPORT_EVERY == 0) {
            report("progress");
        }
    }

    private static void report(String when) {
        long total = avoided + verified;
        if (total == 0) {
            return;
        }
        double meanMicros = verified == 0 ? 0 : verifiedNanos / 1000.0 / verified;
        LOGGER.info("BootOptim EMF existence index ({}): absent probes answered={} (served={}, verified against stock={}), "
                        + "mean stock absent probe={} us, estimated stock wall avoided={} ms",
                when, total, avoided, verified, String.format("%.1f", meanMicros),
                String.format("%.1f", meanMicros * avoided / 1000.0));
    }

    /**
     * The listing is only complete if the predicate EMF passed lets everything through. Probe it
     * with a few shapes of location; a selective predicate leaves the prefix unindexed.
     */
    private static boolean acceptsEverything(String prefix, Predicate<ResourceLocation> filter) {
        if (filter == null) {
            return false;
        }
        try {
            return filter.test(ResourceLocation.fromNamespaceAndPath("minecraft", prefix + "/probe.jem"))
                    && filter.test(ResourceLocation.fromNamespaceAndPath("bootoptim_probe", prefix + "/a/b/c.properties"))
                    && filter.test(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/probe.png"));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Snapshot inventory(ResourceManager manager) {
        Set<String> classes = new TreeSet<>();
        boolean supported;
        try (var packs = manager.listPacks()) {
            List<PackResources> all = packs.toList();
            supported = !all.isEmpty();
            for (PackResources pack : all) {
                supported &= supportedPack(pack, classes, 0);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            supported = false;
        }
        if (!supported) {
            CompatLog.fallback(MECHANISM, MOD_ID, "unsupported_pack_provider classes=" + classes);
        }
        return new Snapshot(supported);
    }

    /**
     * A pack is trusted when its content cannot differ between the listing and a later probe:
     * it offers no client namespace at all, or it inherits resource, listing and namespace
     * behaviour unchanged from one of the stock static pack classes.
     */
    private static boolean supportedPack(PackResources pack, Set<String> classes, int depth)
            throws ReflectiveOperationException {
        if (pack == null || depth > 8) {
            return false;
        }
        Class<?> type = pack.getClass();
        classes.add(type.getName());
        if (type.getName().equals("net.minecraft.server.packs.CompositePackResources")) {
            Field field = type.getDeclaredField("packResourcesStack");
            if (!field.trySetAccessible() || !(field.get(pack) instanceof List<?> stack) || stack.isEmpty()) {
                return false;
            }
            boolean supported = true;
            for (Object child : stack) {
                if (!(child instanceof PackResources resources)) {
                    return false;
                }
                supported &= supportedPack(resources, classes, depth + 1);
            }
            return supported;
        }
        if (pack.getNamespaces(PackType.CLIENT_RESOURCES).isEmpty()) {
            return true;
        }
        return inheritsStock(type, "getResource", PackType.class, ResourceLocation.class)
                && inheritsStock(type, "listResources", PackType.class, String.class, String.class,
                        PackResources.ResourceOutput.class)
                && inheritsStock(type, "getNamespaces", PackType.class);
    }

    private static boolean inheritsStock(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> owner = type.getMethod(name, parameters).getDeclaringClass();
        return owner == PathPackResources.class
                || owner == FilePackResources.class
                || owner == VanillaPackResources.class;
    }
}
