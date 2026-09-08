package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * Bootstrap-owned, version-pinned bridge for regular-mod trace producers.
 *
 * <p>The bridge is published only when structured tracing is explicitly enabled. Regular GAME-layer callers talk to
 * the platform MBeanServer using only JDK types, so they never load {@link StructuredBootTrace} or depend on its
 * classloader/module identity. The SERVICE layer remains the only owner of the writer, sequence and output file.</p>
 */
public final class StructuredBootTraceBridge implements DynamicMBean {
    public static final String PROTOCOL = "bootoptim.boottrace.bridge/1";
    public static final String OBJECT_NAME = "dev.wachipayox.bootoptim:type=StructuredBootTraceBridge,version=1";

    private static final MBeanInfo INFO = createInfo();

    private final StructuredBootTrace trace;

    private StructuredBootTraceBridge(StructuredBootTrace trace) {
        this.trace = trace;
    }

    public static void publishIfEnabled() {
        String configuredMode = System.getProperty(StructuredBootTrace.MODE_PROPERTY, "off");
        if (configuredMode == null || configuredMode.isBlank() || "off".equalsIgnoreCase(configuredMode.trim())) {
            return;
        }
        publish(StructuredBootTrace.global());
    }

    static boolean publishForTest(StructuredBootTrace trace) {
        return publish(trace);
    }

    static void unpublishForTest() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (Throwable ignored) {
            // Diagnostic bridge cleanup must never become a test/runtime dependency.
        }
    }

    private static boolean publish(StructuredBootTrace trace) {
        if (trace == null || !trace.isEnabled()) {
            return false;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                Object protocol = server.getAttribute(name, "Protocol");
                return PROTOCOL.equals(protocol);
            }
            server.registerMBean(new StructuredBootTraceBridge(trace), name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public Object getAttribute(String attribute) {
        return switch (attribute) {
            case "Protocol" -> PROTOCOL;
            case "OwnerClassLoader" -> describeLoader(StructuredBootTraceBridge.class.getClassLoader());
            case "TraceClassLoader" -> describeLoader(StructuredBootTrace.class.getClassLoader());
            default -> null;
        };
    }

    @Override
    public void setAttribute(Attribute attribute) {
        // Read-only bridge metadata.
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList result = new AttributeList();
        if (attributes == null) {
            return result;
        }
        for (String attribute : attributes) {
            Object value = getAttribute(attribute);
            if (value != null) {
                result.add(new Attribute(attribute, value));
            }
        }
        return result;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
        try {
            return switch (actionName) {
                case "recordProbe" -> {
                    String phase = stringParam(params, 0);
                    String detail = stringParam(params, 1);
                    trace.record(StructuredBootTrace.EventType.MOD_CALLBACK, 0L, 0L, null,
                            phase, -1L, "boot_optim", null, -1L, detail);
                    yield null;
                }
                case "beginTask" -> Long.valueOf(trace.beginTask(
                        stringParam(params, 0),
                        longParam(params, 1),
                        longArrayParam(params, 2),
                        stringParam(params, 3),
                        stringParam(params, 4),
                        longParam(params, 5)));
                case "endTask" -> {
                    trace.endTask(longParam(params, 0), stringParam(params, 1), longParam(params, 2), stringParam(params, 3));
                    yield null;
                }
                case "record" -> {
                    StructuredBootTrace.EventType type = parseEventType(stringParam(params, 0));
                    if (type != null) {
                        trace.record(type,
                                longParam(params, 1),
                                longParam(params, 2),
                                longArrayParam(params, 3),
                                stringParam(params, 4),
                                longParam(params, 5),
                                stringParam(params, 6),
                                stringParam(params, 7),
                                longParam(params, 8),
                                stringParam(params, 9));
                    }
                    yield null;
                }
                default -> throw new NoSuchMethodException(actionName);
            };
        } catch (NoSuchMethodException exception) {
            throw new ReflectionException(exception);
        } catch (Throwable ignored) {
            // Fail open for malformed/obsolete diagnostic callers.
            return null;
        }
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private static StructuredBootTrace.EventType parseEventType(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (StructuredBootTrace.EventType type : StructuredBootTrace.EventType.values()) {
            if (type.wireName().equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    private static String stringParam(Object[] params, int index) {
        if (params == null || index >= params.length || params[index] == null) {
            return null;
        }
        return params[index] instanceof String value ? value : String.valueOf(params[index]);
    }

    private static long longParam(Object[] params, int index) {
        if (params == null || index >= params.length || !(params[index] instanceof Number value)) {
            return 0L;
        }
        return value.longValue();
    }

    private static long[] longArrayParam(Object[] params, int index) {
        if (params == null || index >= params.length || !(params[index] instanceof long[] value)) {
            return null;
        }
        return value;
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + ':' + loader.getName() + '@' + Integer.toHexString(System.identityHashCode(loader));
    }

    private static MBeanInfo createInfo() {
        MBeanAttributeInfo[] attributes = new MBeanAttributeInfo[] {
                new MBeanAttributeInfo("Protocol", String.class.getName(), "Versioned bridge protocol", true, false, false),
                new MBeanAttributeInfo("OwnerClassLoader", String.class.getName(), "Bridge owner classloader", true, false, false),
                new MBeanAttributeInfo("TraceClassLoader", String.class.getName(), "StructuredBootTrace classloader", true, false, false)
        };
        MBeanOperationInfo[] operations = new MBeanOperationInfo[] {
                operation("recordProbe", "Record one coarse regular-mod identity probe",
                        parameter("phase", String.class.getName()), parameter("detail", String.class.getName())),
                operation("beginTask", "Begin a structured task and return its task id",
                        parameter("phase", String.class.getName()), parameter("parentTaskId", "long"),
                        parameter("dependencyIds", "[J"), parameter("modId", String.class.getName()),
                        parameter("resourceId", String.class.getName()), parameter("reloadGeneration", "long")),
                operation("endTask", "End a structured task",
                        parameter("taskId", "long"), parameter("phase", String.class.getName()),
                        parameter("cpuNanos", "long"), parameter("detail", String.class.getName())),
                operation("record", "Record one schema-v1 event",
                        parameter("eventType", String.class.getName()), parameter("taskId", "long"),
                        parameter("parentTaskId", "long"), parameter("dependencyIds", "[J"),
                        parameter("phase", String.class.getName()), parameter("cpuNanos", "long"),
                        parameter("modId", String.class.getName()), parameter("resourceId", String.class.getName()),
                        parameter("reloadGeneration", "long"), parameter("detail", String.class.getName()))
        };
        return new MBeanInfo(StructuredBootTraceBridge.class.getName(),
                "BootOptim bootstrap-owned structured trace bridge", attributes, null, operations, null);
    }

    private static MBeanOperationInfo operation(String name, String description, MBeanParameterInfo... parameters) {
        String returnType = "beginTask".equals(name) ? Long.class.getName() : Void.TYPE.getName();
        return new MBeanOperationInfo(name, description, parameters, returnType, MBeanOperationInfo.ACTION);
    }

    private static MBeanParameterInfo parameter(String name, String type) {
        return new MBeanParameterInfo(name, type, name);
    }
}
