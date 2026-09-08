package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Opt-in identity/callsite probe for anonymous reload listeners owned by Minecraft.
 *
 * <p>The probe never times listener work and never wraps or replaces barriers, futures, executors,
 * resource managers, callbacks, or listener instances. It captures a stack only when a
 * {@code Minecraft$$Lambda} is registered, then correlates the same object identity with the final
 * NeoForge-sorted listener list used to create a reload.</p>
 */
public final class MinecraftReloadLambdaIdentityProfiler {
    public static final String PROPERTY = "boot_optim.profileMinecraftReloadLambdaIdentity";
    public static final String MARKER = "BOOTOPTIM_RELOAD_LAMBDA_ID";

    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ReloadLambdaIdentity");
    private static final String MINECRAFT_CLASS = "net.minecraft.client.Minecraft";
    private static final String MINECRAFT_LAMBDA_PREFIX = MINECRAFT_CLASS + "$$Lambda";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final Map<PreparableReloadListener, Registration> REGISTRATIONS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final AtomicInteger RELOAD_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger CAPTURE_COUNT = new AtomicInteger();
    private static final AtomicLong CAPTURE_NANOS = new AtomicLong();

    private MinecraftReloadLambdaIdentityProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void onRegister(PreparableReloadListener listener) {
        if (!ENABLED || listener == null || !isMinecraftLambda(listener)) {
            return;
        }

        long started = System.nanoTime();
        StackTraceElement callsite = Arrays.stream(Thread.currentThread().getStackTrace())
                .filter(frame -> MINECRAFT_CLASS.equals(frame.getClassName()))
                .findFirst()
                .orElse(null);
        String capturedFieldTypes = capturedFieldTypes(listener.getClass());
        long captureNanos = System.nanoTime() - started;

        Registration registration = new Registration(
                System.identityHashCode(listener),
                stableClassName(listener.getClass().getName()),
                callsite,
                capturedFieldTypes,
                captureNanos);
        REGISTRATIONS.put(listener, registration);
        CAPTURE_COUNT.incrementAndGet();
        CAPTURE_NANOS.addAndGet(captureNanos);

        LOGGER.info(
                "{} event=registered identity={} class={} callsite_class={} callsite_method={} callsite_file={} callsite_line={} captured_field_types=\"{}\" capture_us={}",
                MARKER,
                registration.identity,
                registration.className,
                frameClass(callsite),
                frameMethod(callsite),
                frameFile(callsite),
                frameLine(callsite),
                registration.capturedFieldTypes,
                formatMicros(captureNanos));
    }

    public static void onCreateReload(List<PreparableReloadListener> listeners) {
        if (!ENABLED || listeners == null) {
            return;
        }

        int reloadSequence = RELOAD_SEQUENCE.incrementAndGet();
        int minecraftLambdaCount = 0;
        for (int index = 0; index < listeners.size(); index++) {
            PreparableReloadListener listener = listeners.get(index);
            if (!isMinecraftLambda(listener)) {
                continue;
            }
            minecraftLambdaCount++;
            Registration registration = REGISTRATIONS.get(listener);
            StackTraceElement callsite = registration == null ? null : registration.callsite;
            String className = registration == null
                    ? stableClassName(listener.getClass().getName())
                    : registration.className;
            int identity = System.identityHashCode(listener);
            String capturedFieldTypes = registration == null
                    ? capturedFieldTypes(listener.getClass())
                    : registration.capturedFieldTypes;

            LOGGER.info(
                    "{} event=ordered reload_seq={} index={} list_size={} target={} identity={} registration_match={} class={} callsite_class={} callsite_method={} callsite_file={} callsite_line={} captured_field_types=\"{}\"",
                    MARKER,
                    reloadSequence,
                    index,
                    listeners.size(),
                    index == 25 || index == 26,
                    identity,
                    registration != null && registration.identity == identity,
                    className,
                    frameClass(callsite),
                    frameMethod(callsite),
                    frameFile(callsite),
                    frameLine(callsite),
                    capturedFieldTypes);
        }

        LOGGER.info(
                "{} event=summary reload_seq={} list_size={} minecraft_lambda_count={} registered_lambda_count={} registration_capture_total_us={}",
                MARKER,
                reloadSequence,
                listeners.size(),
                minecraftLambdaCount,
                CAPTURE_COUNT.get(),
                formatMicros(CAPTURE_NANOS.get()));
    }

    private static boolean isMinecraftLambda(PreparableReloadListener listener) {
        return stableClassName(listener.getClass().getName()).startsWith(MINECRAFT_LAMBDA_PREFIX);
    }

    private static String stableClassName(String className) {
        int hiddenSuffix = className.indexOf('/');
        return hiddenSuffix >= 0 ? className.substring(0, hiddenSuffix) : className;
    }

    private static String capturedFieldTypes(Class<?> lambdaClass) {
        try {
            return Arrays.stream(lambdaClass.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(Field::getType)
                    .map(Class::getName)
                    .collect(Collectors.joining(","));
        } catch (Throwable throwable) {
            return "<unavailable:" + throwable.getClass().getSimpleName() + ">";
        }
    }

    private static String frameClass(StackTraceElement frame) {
        return frame == null ? "<unavailable>" : frame.getClassName();
    }

    private static String frameMethod(StackTraceElement frame) {
        return frame == null ? "<unavailable>" : frame.getMethodName();
    }

    private static String frameFile(StackTraceElement frame) {
        return frame == null || frame.getFileName() == null ? "<unavailable>" : frame.getFileName();
    }

    private static int frameLine(StackTraceElement frame) {
        return frame == null ? -1 : frame.getLineNumber();
    }

    private static String formatMicros(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000.0D);
    }

    private record Registration(
            int identity,
            String className,
            StackTraceElement callsite,
            String capturedFieldTypes,
            long captureNanos) {
    }
}
