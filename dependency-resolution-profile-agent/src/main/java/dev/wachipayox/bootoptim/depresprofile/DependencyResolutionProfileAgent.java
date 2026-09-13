package dev.wachipayox.bootoptim.depresprofile;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Diagnostic-only, default-off observer for FML 4.0.43 dependency discovery and resolution. */
public final class DependencyResolutionProfileAgent {
    private static final String MOD_DISCOVERER = "net.neoforged.fml.loading.moddiscovery.ModDiscoverer";
    private static final String UNIQUE_LIST = "net.neoforged.fml.loading.UniqueModListBuilder";
    private static final String MOD_VALIDATOR = "net.neoforged.fml.loading.moddiscovery.ModValidator";
    private static final String MOD_FILE = "net.neoforged.fml.loading.moddiscovery.ModFile";
    private static final String MOD_SORTER = "net.neoforged.fml.loading.ModSorter";
    private static final String TOPO_SORT = "net.neoforged.fml.loading.toposort.TopologicalSort";
    private static final String DEPENDENCY_LOCATOR = "net.neoforged.neoforgespi.locating.IDependencyLocator";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/depresprofile/Recorder.class",
            "dev/wachipayox/bootoptim/depresprofile/Recorder$Event.class"
    };

    private DependencyResolutionProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.dependencyResolutionProfile")) return;
        System.err.println("BOOTOPTIM_DEPRES_PROFILE_ARMED expected_fml="
                + System.getProperty("boot_optim.dependencyResolutionProfile.expectedFmlVersion", "4.0.43"));
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_DEPRES_PROFILE_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, DependencyResolutionProfileAgent.class, Recorder.class)
                .type(named(MOD_DISCOVERER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(DiscoverAdvice.class).on(named("discoverMods").and(takesArguments(0)))))
                .type(hasSuperType(named(DEPENDENCY_LOCATOR)).and(not(isInterface())))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(LocatorAdvice.class).on(named("scanMods").and(takesArguments(2)))))
                .type(named(UNIQUE_LIST))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(UniqueListAdvice.class).on(named("buildUniqueList").and(takesArguments(0)))))
                .type(named(MOD_VALIDATOR))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(Stage1Advice.class).on(named("stage1Validation").and(takesArguments(0))))
                        .visit(Advice.to(Stage2Advice.class).on(named("stage2Validation").and(takesArguments(0)))))
                .type(named(MOD_FILE))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(IdentifyModsAdvice.class).on(named("identifyMods").and(takesArguments(0))))
                        .visit(Advice.to(IdentifyLanguageAdvice.class).on(named("identifyLanguage").and(takesArguments(0)))))
                .type(named(MOD_SORTER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(SorterTotalAdvice.class).on(named("sort").and(takesArguments(3))))
                        .visit(Advice.to(DependencyVersionsAdvice.class).on(named("verifyDependencyVersions").and(takesArguments(0))))
                        .visit(Advice.to(GraphSortAdvice.class).on(named("sort").and(takesArguments(1)))))
                .type(named(TOPO_SORT))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(TopoAdvice.class).on(named("topologicalSort"))))
                .installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-depres-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = DependencyResolutionProfileAgent.class.getClassLoader();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(bridge))) {
            for (String resource : BOOTSTRAP_BRIDGE_CLASSES) {
                try (InputStream input = loader.getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("missing bootstrap bridge class: " + resource);
                    output.putNextEntry(new JarEntry(resource));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        return new JarFile(bridge.toFile());
    }

    public static long[] begin(String kind, Class<?> owner) {
        try { return Recorder.beginFml(kind, owner); } catch (Throwable ignored) { return null; }
    }

    public static void end(String kind, Class<?> owner, long[] state, Throwable thrown) {
        try { Recorder.end(kind, owner, state, thrown); } catch (Throwable ignored) {}
    }

    public static final class DiscoverAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("discover_total", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("discover_total", owner, state, thrown); }
    }

    public static final class LocatorAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginLocator(owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("dependency_locator", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class UniqueListAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("unique_list", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("unique_list", owner, state, thrown); }
    }

    public static final class Stage1Advice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("stage1_validation", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("stage1_validation", owner, state, thrown); }
    }

    public static final class Stage2Advice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("stage2_validation", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("stage2_validation", owner, state, thrown); }
    }

    public static final class IdentifyModsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("identify_mods", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("identify_mods", owner, state, thrown); }
    }

    public static final class IdentifyLanguageAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("identify_language", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("identify_language", owner, state, thrown); }
    }

    public static final class SorterTotalAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("sorter_total", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("sorter_total", owner, state, thrown); }
    }

    public static final class DependencyVersionsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("dependency_versions", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("dependency_versions", owner, state, thrown); }
    }

    public static final class GraphSortAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("graph_sort", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("graph_sort", owner, state, thrown); }
    }

    public static final class TopoAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { return begin("topological_sort", owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { end("topological_sort", owner, state, thrown); }
    }
}
