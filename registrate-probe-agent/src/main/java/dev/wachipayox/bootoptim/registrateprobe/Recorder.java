package dev.wachipayox.bootoptim.registrateprobe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Recorder {
    private static final String CREATE_SOURCE = "ac0c444d9828da3453ae8cc65338e8de063286fb";
    private static final String REGISTRATE_LINEAGE = "fd0f4e7fa1e53090cbe2024483b82743366d4c87";
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static volatile State completed;
    private static volatile Class<?> registrateClass;
    private static volatile String createVersion;
    private static volatile String sableVersion;
    private static volatile String registratePackageVersion;
    private static volatile String registrateCodeSource;

    private Recorder() {}

    public static void installShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::flush, "bootoptim-registrate-probe-flush"));
    }

    public static void clinitBegin() {
        State existing = STATE.get();
        if (existing != null) {
            existing.errors++;
            return;
        }
        long now = System.nanoTime();
        State state = new State();
        state.tid = Thread.currentThread().threadId();
        state.threadName = Thread.currentThread().getName();
        state.clinitStartNs = now;
        state.lastBoundaryNs = now;
        STATE.set(state);
    }

    public static void entryBegin(String name) {
        State state = active();
        if (state == null) return;
        if (state.currentName == null) {
            state.currentName = name;
            state.windowStartNs = state.lastBoundaryNs;
            state.creationNs = state.visualNs = state.validNs = state.rendererNs = state.registerNs = 0L;
            state.listenerVisualNs = state.listenerRendererNs = state.listenerOtherNs = 0L;
            state.listenerVisualCount = state.listenerRendererCount = state.listenerOtherCount = 0;
        } else if (!state.currentName.equals(name)) {
            state.errors++;
        }
    }

    public static void opEnter(String category) {
        State state = active();
        if (state == null || state.currentName == null) return;
        long now = System.nanoTime();
        Frame frame = new Frame();
        frame.category = category;
        frame.startNs = now;
        frame.parent = state.stack;
        state.stack = frame;
    }

    public static long opExit(String category, Throwable thrown) {
        long now = System.nanoTime();
        State state = active();
        if (state == null || state.currentName == null) return now;
        Frame frame = state.stack;
        if (frame == null || !category.equals(frame.category)) {
            state.errors++;
            if (thrown != null) state.throwsSeen++;
            return now;
        }
        state.stack = frame.parent;
        long elapsed = now - frame.startNs;
        long exclusive = elapsed - frame.childNs;
        if (exclusive < 0) {
            state.errors++;
            exclusive = 0;
        }
        addOperation(state, category, exclusive);
        if (frame.parent != null) frame.parent.childNs += elapsed;
        if (thrown != null) state.throwsSeen++;
        return now;
    }

    public static long listenerEnter() {
        State state = active();
        if (state == null || state.currentName == null) return 0L;
        state.listenerDepth++;
        return state.listenerDepth == 1 ? System.nanoTime() : -1L;
    }

    public static void listenerExit(long start, Throwable thrown) {
        State state = active();
        if (state == null || state.currentName == null || start == 0L) return;
        try {
            if (start > 0L) {
                long elapsed = System.nanoTime() - start;
                String parent = state.stack == null ? "other" : state.stack.category;
                if ("visual".equals(parent)) {
                    state.listenerVisualCount++;
                    state.listenerVisualNs += elapsed;
                } else if ("renderer".equals(parent)) {
                    state.listenerRendererCount++;
                    state.listenerRendererNs += elapsed;
                } else {
                    state.listenerOtherCount++;
                    state.listenerOtherNs += elapsed;
                }
            }
            if (thrown != null) state.throwsSeen++;
        } finally {
            state.listenerDepth--;
            if (state.listenerDepth < 0) {
                state.listenerDepth = 0;
                state.errors++;
            }
        }
    }

    public static void entryEnd(long endNs, Throwable thrown) {
        State state = active();
        if (state == null || state.currentName == null) return;
        if (state.stack != null) state.errors++;
        long wall = endNs - state.windowStartNs;
        long tracked = state.creationNs + state.visualNs + state.validNs + state.rendererNs + state.registerNs;
        long residual = wall - tracked;
        if (residual < 0) {
            state.errors++;
            residual = 0;
        }
        EntryResult row = new EntryResult();
        row.index = state.entries.size();
        row.name = state.currentName;
        row.wallNs = wall;
        row.creationNs = state.creationNs;
        row.visualNs = state.visualNs;
        row.validNs = state.validNs;
        row.rendererNs = state.rendererNs;
        row.registerNs = state.registerNs;
        row.residualNs = residual;
        row.listenerVisualCount = state.listenerVisualCount;
        row.listenerVisualNs = state.listenerVisualNs;
        row.listenerRendererCount = state.listenerRendererCount;
        row.listenerRendererNs = state.listenerRendererNs;
        row.listenerOtherCount = state.listenerOtherCount;
        row.listenerOtherNs = state.listenerOtherNs;
        row.threw = thrown != null;
        state.entries.add(row);
        state.lastBoundaryNs = endNs;
        state.currentName = null;
        if (thrown != null) state.throwsSeen++;
    }

    public static void observeRegistrateClass(Class<?> owner) {
        if (owner != null && "com.tterrag.registrate.builders.BlockEntityBuilder".equals(owner.getName())) {
            registrateClass = owner;
        }
    }

    public static void clinitEnd(Throwable thrown) {
        State state = active();
        if (state == null) return;
        long end = System.nanoTime();
        state.clinitEndNs = end;
        state.tailNs = end - state.lastBoundaryNs;
        if (state.currentName != null || state.stack != null || state.listenerDepth != 0) state.errors++;
        if (thrown != null) state.throwsSeen++;
        completed = state;
        STATE.remove();

        // Identity work intentionally happens after the measured <clinit> endpoint.
        createVersion = modVersion("create");
        sableVersion = modVersion("sable");
        Class<?> registrate = registrateClass;
        if (registrate != null) {
            try {
                Package pkg = registrate.getPackage();
                registratePackageVersion = pkg == null ? null : pkg.getImplementationVersion();
                if (registrate.getProtectionDomain() != null
                        && registrate.getProtectionDomain().getCodeSource() != null
                        && registrate.getProtectionDomain().getCodeSource().getLocation() != null) {
                    registrateCodeSource = registrate.getProtectionDomain().getCodeSource().getLocation().toString();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static State active() {
        State state = STATE.get();
        if (state == null) return null;
        if (Thread.currentThread().threadId() != state.tid) {
            state.errors++;
            return null;
        }
        return state;
    }

    private static void addOperation(State state, String category, long ns) {
        switch (category) {
            case "creation" -> state.creationNs += ns;
            case "visual" -> state.visualNs += ns;
            case "valid_blocks" -> state.validNs += ns;
            case "renderer" -> state.rendererNs += ns;
            case "register_accept" -> state.registerNs += ns;
            default -> state.errors++;
        }
    }

    private static String modVersion(String modId) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList", false, loader);
            Object modList = modListClass.getMethod("get").invoke(null);
            Object optional = modListClass.getMethod("getModContainerById", String.class).invoke(modList, modId);
            if (!(optional instanceof Optional<?> value) || value.isEmpty()) return null;
            Object container = value.get();
            Method getModInfo = container.getClass().getMethod("getModInfo");
            Object info = getModInfo.invoke(container);
            Method getVersion = info.getClass().getMethod("getVersion");
            Object version = getVersion.invoke(info);
            return version == null ? null : version.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void flush() {
        if (!FLUSHED.compareAndSet(false, true)) return;
        State state = completed;
        if (state == null) {
            System.out.println("BOOTOPTIM_REGISTRATE_PROBE {\"kind\":\"disabled\",\"reason\":\"no_completed_clinit\"}");
            return;
        }
        System.out.println("BOOTOPTIM_REGISTRATE_PROBE {\"kind\":\"header\",\"create_version\":" + q(createVersion)
                + ",\"create_source\":" + q(CREATE_SOURCE)
                + ",\"registrate_lineage\":" + q(REGISTRATE_LINEAGE)
                + ",\"registrate_package_version\":" + q(registratePackageVersion)
                + ",\"registrate_code_source\":" + q(registrateCodeSource)
                + ",\"sable_version\":" + q(sableVersion)
                + ",\"thread\":" + q(state.threadName) + ",\"tid\":" + state.tid
                + ",\"entry_count\":" + state.entries.size()
                + ",\"clinit_ns\":" + (state.clinitEndNs - state.clinitStartNs)
                + ",\"tail_ns\":" + state.tailNs
                + ",\"errors\":" + state.errors + ",\"throws\":" + state.throwsSeen + "}");
        for (EntryResult row : state.entries) {
            System.out.println("BOOTOPTIM_REGISTRATE_PROBE {\"kind\":\"entry\",\"index\":" + row.index
                    + ",\"name\":" + q(row.name) + ",\"wall_ns\":" + row.wallNs
                    + ",\"creation_ns\":" + row.creationNs + ",\"visual_ns\":" + row.visualNs
                    + ",\"valid_blocks_ns\":" + row.validNs + ",\"renderer_ns\":" + row.rendererNs
                    + ",\"register_accept_ns\":" + row.registerNs + ",\"residual_ns\":" + row.residualNs
                    + ",\"listener_visual_count\":" + row.listenerVisualCount + ",\"listener_visual_ns\":" + row.listenerVisualNs
                    + ",\"listener_renderer_count\":" + row.listenerRendererCount + ",\"listener_renderer_ns\":" + row.listenerRendererNs
                    + ",\"listener_other_count\":" + row.listenerOtherCount + ",\"listener_other_ns\":" + row.listenerOtherNs
                    + ",\"threw\":" + row.threw + "}");
        }
    }

    private static String q(String value) {
        if (value == null) return "null";
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    static final class State {
        long tid;
        String threadName;
        long clinitStartNs, clinitEndNs, lastBoundaryNs, windowStartNs, tailNs;
        String currentName;
        Frame stack;
        int listenerDepth;
        long creationNs, visualNs, validNs, rendererNs, registerNs;
        int listenerVisualCount, listenerRendererCount, listenerOtherCount;
        long listenerVisualNs, listenerRendererNs, listenerOtherNs;
        int errors, throwsSeen;
        final List<EntryResult> entries = new ArrayList<>(128);
    }

    static final class Frame {
        String category;
        long startNs, childNs;
        Frame parent;
    }

    static final class EntryResult {
        int index;
        String name;
        long wallNs, creationNs, visualNs, validNs, rendererNs, registerNs, residualNs;
        int listenerVisualCount, listenerRendererCount, listenerOtherCount;
        long listenerVisualNs, listenerRendererNs, listenerOtherNs;
        boolean threw;
    }
}
