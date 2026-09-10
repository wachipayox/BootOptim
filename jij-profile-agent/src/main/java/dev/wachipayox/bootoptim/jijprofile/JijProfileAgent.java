package dev.wachipayox.bootoptim.jijprofile;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Diagnostic-only profiler for stock FML 4.0.43 Jar-in-Jar discovery. */
public final class JijProfileAgent {
    private static final String LOCATOR = "net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator";
    private static final String PIPELINE = "net.neoforged.fml.loading.moddiscovery.ModDiscoverer$DiscoveryPipeline";
    private static final String JIJ_PROVIDER = "net.neoforged.jarjar.nio.layzip.LayeredZipFileSystemProvider";
    private static final String SELECTOR = "net.neoforged.jarjar.selection.JarSelector";
    private static final String METADATA_IO = "net.neoforged.jarjar.metadata.MetadataIOHandler";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/jijprofile/Recorder.class",
            "dev/wachipayox/bootoptim/jijprofile/Recorder$Event.class"
    };

    private JijProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.jijProfile")) return;
        System.err.println("BOOTOPTIM_JIJ_PROFILE_ARMED target=" + LOCATOR);
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_JIJ_PROFILE_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, JijProfileAgent.class, Recorder.class)
                .type(named(LOCATOR))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ScanAdvice.class).on(named("scanMods").and(takesArguments(2))))
                        .visit(Advice.to(LoadAdvice.class).on(named("loadModFileFrom").and(takesArguments(3))))
                        .visit(Advice.to(DescriptorAdvice.class).on(named("loadResourceFromModFile").and(takesArguments(2))))
                        .visit(Advice.to(IdentifyAdvice.class).on(named("identifyMod").and(takesArguments(1))))
                        .visit(Advice.to(SelectorFailureAdvice.class).on(named("exception").and(takesArguments(1)))))
                .type(named(PIPELINE))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ReaderAdvice.class).on(named("readModFile").and(takesArguments(2)))))
                .type(named(JIJ_PROVIDER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(FileSystemAdvice.class).on(named("newFileSystem").and(takesArguments(2)))))
                .type(named(SELECTOR))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(SelectorPhaseAdvice.class).on(named("detectAndSelect").and(takesArguments(5))))
                        .visit(Advice.to(SelectorPhaseAdvice.class).on(named("detect").and(takesArguments(4))))
                        .visit(Advice.to(SelectorPhaseAdvice.class).on(named("recursivelyDetectContainedJars").and(takesArguments(4)))))
                .type(named(METADATA_IO))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(MetadataAdvice.class).on(named("fromStream").and(takesArguments(1)))))
                .installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-jij-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = JijProfileAgent.class.getClassLoader();
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

    public static final class ScanAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.scanBegin(owner); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.scanEnd(start, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class LoadAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) Object parent, @Advice.Argument(1) Path relativePath,
                @Advice.Return Optional<?> result, @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try {
                Recorder.loadEnd(parent, relativePath == null ? null : relativePath.toString(),
                        result == null ? null : result.orElse(null), start, thrown);
            } catch (Throwable ignored) {}
        }
    }

    public static final class DescriptorAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) Object parent, @Advice.Argument(1) Path relativePath,
                @Advice.Return Optional<?> result, @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try {
                Recorder.descriptorEnd(parent, relativePath == null ? null : relativePath.toString(),
                        result == null ? null : result.orElse(null), start, thrown);
            } catch (Throwable ignored) {}
        }
    }

    public static final class IdentifyAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) Object modFile, @Advice.Return String identity,
                @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.identifyEnd(modFile, identity, start, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class SelectorFailureAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.simpleEnd("selector_failure_callback", start, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class ReaderAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) Object jarContents, @Advice.Return Object result,
                @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.readerEnd(jarContents, result != null, start, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class FileSystemAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Argument(0) URI uri) {
            try { return Recorder.fileSystemBegin(uri == null ? null : uri.toString()); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) URI uri, @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.fileSystemEnd(uri == null ? null : uri.toString(), start, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class SelectorPhaseAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin("#m") String method, @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.selectorPhaseEnd(method, start, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class MetadataAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try { return Recorder.intervalBegin(); } catch (Throwable ignored) { return 0L; }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) Object input, @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try { Recorder.metadataEnd(input, start, thrown); } catch (Throwable ignored) {}
        }
    }
}
