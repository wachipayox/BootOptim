package dev.wachipayox.bootoptim.fmlchain;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class Recorder {
    private static final String EXPECTED_FML_VERSION = System.getProperty(
            "boot_optim.fmlChainProfile.expectedFmlVersion", "4.0.43");
    private static final String EXPECTED_CREATE_VERSION = System.getProperty(
            "boot_optim.fmlChainProfile.expectedCreateVersion", "6.0.10");
    private static final String EXPECTED_CREATE_SOURCE = "ac0c444d9828da3453ae8cc65338e8de063286fb";
    private static final Path OUTPUT = Path.of(System.getProperty(
            "boot_optim.fmlChainProfile.output", "bootoptim-fml-chain.jsonl"));
    private static final long ORIGIN_NS = System.nanoTime();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> CREATE_ACTIVE = new ThreadLocal<>();
    private static volatile boolean active;
    private static volatile boolean versionAccepted;

    private Recorder() {}

    public static void installShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::flush, "bootoptim-fml-chain-flush"));
    }

    public static void beginGate(Class<?> owner) {
        String moduleVersion = null;
        String moduleName = null;
        String packageVersion = null;
        try {
            if (owner != null) {
                Module module = owner.getModule();
                if (module != null) {
                    moduleName = module.getName();
                    var descriptor = module.getDescriptor();
                    if (descriptor != null) moduleVersion = descriptor.rawVersion().orElse(null);
                }
                Package pkg = owner.getPackage();
                packageVersion = pkg == null ? null : pkg.getImplementationVersion();
            }
        } catch (Throwable ignored) {
        }

        versionAccepted = "fml_loader".equals(moduleName) && EXPECTED_FML_VERSION.equals(moduleVersion);
        recordRaw("profile_header", null,
                "expected_fml=" + EXPECTED_FML_VERSION
                        + ";module_name=" + String.valueOf(moduleName)
                        + ";module_fml=" + String.valueOf(moduleVersion)
                        + ";package_fml=" + String.valueOf(packageVersion));
        if (!versionAccepted) {
            recordRaw("profile_disabled", null, "reason=fml_version_mismatch");
            active = false;
            return;
        }
        active = true;
        recordRaw("gate_begin", null, "phase=Mod Construction");
    }

    public static void endGate(Throwable thrown) {
        if (active) recordRaw("gate_end", null, throwableDetail(thrown));
        CREATE_ACTIVE.remove();
        active = false;
    }

    public static void dependency(Object modInfo, Object result) {
        if (!active) return;
        String mod = modId(modInfo);
        StringBuilder deps = new StringBuilder();
        if (result instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                String dep = modId(value);
                if (dep == null) continue;
                if (!deps.isEmpty()) deps.append(',');
                deps.append(dep);
            }
        }
        recordRaw("dependencies", mod, deps.toString());
    }

    public static void constructBegin(Object container) {
        if (active) recordRaw("construct_begin", modId(container), null);
    }

    public static void constructEnd(Object container, Throwable thrown) {
        if (active) recordRaw("construct_end", modId(container), throwableDetail(thrown));
    }

    public static void subscriberBegin(Object container) {
        if (active) recordRaw("subscriber_begin", modId(container), null);
    }

    public static void subscriberEnd(Object container, Throwable thrown) {
        if (active) recordRaw("subscriber_end", modId(container), throwableDetail(thrown));
    }

    public static boolean constructEventBegin(Object container, Object event) {
        if (!active || event == null
                || !"net.neoforged.fml.event.lifecycle.FMLConstructModEvent".equals(event.getClass().getName())) {
            return false;
        }
        recordRaw("construct_event_begin", modId(container), null);
        return true;
    }

    public static void constructEventEnd(Object container, boolean recorded, Throwable thrown) {
        if (recorded) recordRaw("construct_event_end", modId(container), throwableDetail(thrown));
    }

    public static void createCtorBegin(Object modContainer, Class<?> owner) {
        if (!active) return;
        String mod = modId(modContainer);
        String createVersion = modVersion(modContainer);
        String packageVersion = null;
        String codeSource = null;
        try {
            Package pkg = owner == null ? null : owner.getPackage();
            packageVersion = pkg == null ? null : pkg.getImplementationVersion();
            if (owner != null && owner.getProtectionDomain() != null
                    && owner.getProtectionDomain().getCodeSource() != null
                    && owner.getProtectionDomain().getCodeSource().getLocation() != null) {
                codeSource = owner.getProtectionDomain().getCodeSource().getLocation().toString();
            }
        } catch (Throwable ignored) {
        }

        recordRaw("create_profile_header", "create",
                "expected_create=" + EXPECTED_CREATE_VERSION
                        + ";observed_mod=" + String.valueOf(mod)
                        + ";observed_create=" + String.valueOf(createVersion)
                        + ";package_create=" + String.valueOf(packageVersion)
                        + ";source_pin=" + EXPECTED_CREATE_SOURCE
                        + ";code_source=" + String.valueOf(codeSource));
        if (!"create".equals(mod) || !EXPECTED_CREATE_VERSION.equals(createVersion)) {
            recordRaw("create_profile_disabled", "create", "reason=create_version_mismatch");
            CREATE_ACTIVE.remove();
            return;
        }
        CREATE_ACTIVE.set(Boolean.TRUE);
        recordRaw("create_ctor_begin", "create", null);
    }

    public static void createBoundary(String origin, Throwable thrown) {
        if (!active || !Boolean.TRUE.equals(CREATE_ACTIVE.get())) return;
        String detail = "origin=" + origin;
        if (thrown != null) detail += ";throw=" + thrown.getClass().getName();
        recordRaw("create_boundary", "create", detail);
    }

    public static void createCtorEnd(Throwable thrown) {
        try {
            if (active && Boolean.TRUE.equals(CREATE_ACTIVE.get())) {
                recordRaw("create_ctor_end", "create", throwableDetail(thrown));
            }
        } finally {
            CREATE_ACTIVE.remove();
        }
    }

    private static String modVersion(Object container) {
        if (container == null) return null;
        try {
            Method getModInfo = container.getClass().getMethod("getModInfo");
            Object info = getModInfo.invoke(container);
            if (info == null) return null;
            Method getVersion = info.getClass().getMethod("getVersion");
            Object version = getVersion.invoke(info);
            return version == null ? null : version.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String throwableDetail(Throwable thrown) {
        return thrown == null ? null : "throw=" + thrown.getClass().getName();
    }

    private static String modId(Object value) {
        if (value == null) return null;
        try {
            Method method = value.getClass().getMethod("getModId");
            Object result = method.invoke(value);
            return result == null ? null : result.toString();
        } catch (Throwable ignored) {
            return value.getClass().getName();
        }
    }

    private static void recordRaw(String kind, String mod, String detail) {
        Thread thread = Thread.currentThread();
        EVENTS.add(new Event(
                SEQUENCE.incrementAndGet(),
                System.nanoTime() - ORIGIN_NS,
                kind,
                mod,
                detail,
                thread.getName(),
                thread.threadId()));
    }

    public static void flush() {
        if (!FLUSHED.compareAndSet(false, true)) return;
        var snapshot = new ArrayList<>(EVENTS);
        snapshot.sort(Comparator.comparingLong(Event::ns).thenComparingLong(Event::sequence));
        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    OUTPUT,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                for (Event event : snapshot) {
                    writer.write(event.toJson());
                    writer.newLine();
                }
            }
        } catch (IOException error) {
            System.err.println("BOOTOPTIM_FML_CHAIN_FLUSH_FAILED " + error);
        }
    }

    private record Event(long sequence, long ns, String kind, String mod, String detail, String thread, long threadId) {
        String toJson() {
            return "{\"seq\":" + sequence
                    + ",\"ns\":" + ns
                    + ",\"kind\":\"" + escape(kind) + "\""
                    + ",\"mod\":" + nullable(mod)
                    + ",\"detail\":" + nullable(detail)
                    + ",\"thread\":\"" + escape(thread) + "\""
                    + ",\"tid\":" + threadId + "}";
        }

        private static String nullable(String value) {
            return value == null ? "null" : "\"" + escape(value) + "\"";
        }

        private static String escape(String value) {
            if (value == null) return "";
            StringBuilder out = new StringBuilder(value.length() + 8);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '"' -> out.append("\\\"");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.toString();
        }
    }
}
