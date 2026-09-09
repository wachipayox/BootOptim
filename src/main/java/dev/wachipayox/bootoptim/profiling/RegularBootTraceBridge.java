package dev.wachipayox.bootoptim.profiling;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * GAME-layer client for the bootstrap-owned structured trace bridge.
 *
 * <p>This class intentionally uses only JDK/JMX types and does not link against trace-core. That keeps the regular
 * mod independent of SERVICE-layer class identity while still allowing coarse resource/model producers to append to
 * the one bootstrap-owned sequence/file. Every call is default-off and fail-open.</p>
 */
public final class RegularBootTraceBridge {
    private static final String MODE_PROPERTY = "boot_optim.bootTrace.mode";
    private static final String PROTOCOL = "bootoptim.boottrace.bridge/1";
    private static final String OBJECT_NAME = "dev.wachipayox.bootoptim:type=StructuredBootTraceBridge,version=1";

    private static volatile ObjectName objectName;
    private static volatile boolean unavailable;

    private RegularBootTraceBridge() {
    }

    public static void recordProbe(String phase) {
        if (!configured() || phase == null || phase.isBlank()) {
            return;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = name();
            if (!compatible(server, name)) {
                return;
            }
            server.invoke(name, "recordProbe",
                    new Object[] {phase, describeLoader(RegularBootTraceBridge.class.getClassLoader())},
                    new String[] {String.class.getName(), String.class.getName()});
        } catch (Throwable ignored) {
            unavailable = true;
        }
    }

    public static long beginTask(String phase, long parentTaskId, long[] dependencyIds,
            String modId, String resourceId, long reloadGeneration) {
        if (!configured()) {
            return 0L;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = name();
            if (!compatible(server, name)) {
                return 0L;
            }
            Object result = server.invoke(name, "beginTask",
                    new Object[] {phase, parentTaskId, dependencyIds, modId, resourceId, reloadGeneration},
                    new String[] {String.class.getName(), "long", "[J", String.class.getName(),
                            String.class.getName(), "long"});
            return result instanceof Number value ? value.longValue() : 0L;
        } catch (Throwable ignored) {
            unavailable = true;
            return 0L;
        }
    }

    public static void endTask(long taskId, String phase, long cpuNanos, String detail) {
        if (!configured() || taskId == 0L) {
            return;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = name();
            if (!compatible(server, name)) {
                return;
            }
            server.invoke(name, "endTask",
                    new Object[] {taskId, phase, cpuNanos, detail},
                    new String[] {"long", String.class.getName(), "long", String.class.getName()});
        } catch (Throwable ignored) {
            unavailable = true;
        }
    }

    public static void record(String eventType, long taskId, long parentTaskId, long[] dependencyIds,
            String phase, long cpuNanos, String modId, String resourceId, long reloadGeneration, String detail) {
        if (!configured()) {
            return;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = name();
            if (!compatible(server, name)) {
                return;
            }
            server.invoke(name, "record",
                    new Object[] {eventType, taskId, parentTaskId, dependencyIds, phase, cpuNanos,
                            modId, resourceId, reloadGeneration, detail},
                    new String[] {String.class.getName(), "long", "long", "[J", String.class.getName(), "long",
                            String.class.getName(), String.class.getName(), "long", String.class.getName()});
        } catch (Throwable ignored) {
            unavailable = true;
        }
    }

    private static boolean configured() {
        if (unavailable) {
            return false;
        }
        String mode = System.getProperty(MODE_PROPERTY, "off");
        return mode != null && !mode.isBlank() && !"off".equalsIgnoreCase(mode.trim());
    }

    private static ObjectName name() throws Exception {
        ObjectName cached = objectName;
        if (cached != null) {
            return cached;
        }
        cached = new ObjectName(OBJECT_NAME);
        objectName = cached;
        return cached;
    }

    private static boolean compatible(MBeanServer server, ObjectName name) throws Exception {
        if (!server.isRegistered(name)) {
            return false;
        }
        Object protocol = server.getAttribute(name, "Protocol");
        return PROTOCOL.equals(protocol);
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + ':' + loader.getName() + '@' + Integer.toHexString(System.identityHashCode(loader));
    }
}
