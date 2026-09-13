package dev.wachipayox.bootoptim.jijprofile;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
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
    private static final String TARGET = "net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/jijprofile/Recorder.class",
            "dev/wachipayox/bootoptim/jijprofile/Recorder$Event.class"
    };

    private JijProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.jijProfile")) return;
        System.err.println("BOOTOPTIM_JIJ_PROFILE_ARMED target=" + TARGET);
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
                .type(named(TARGET))
                .transform((builder, type, classLoader, module, protectionDomain) -> {
                    System.err.println("BOOTOPTIM_JIJ_PROFILE_TRANSFORM target=" + type.getName()
                            + " module=" + (module == null ? "null" : module.getActualName()));
                    return builder
                            .visit(Advice.to(ScanAdvice.class)
                                    .on(named("scanMods").and(takesArguments(2))))
                            .visit(Advice.to(LoadAdvice.class)
                                    .on(named("loadModFileFrom").and(takesArguments(3))));
                })
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
            try {
                return Recorder.scanBegin(owner);
            } catch (Throwable ignored) {
                return 0L;
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            try {
                Recorder.scanEnd(start, thrown);
            } catch (Throwable ignored) {
            }
        }
    }

    public static final class LoadAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            try {
                return Recorder.intervalBegin();
            } catch (Throwable ignored) {
                return 0L;
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.Argument(0) Object parent,
                @Advice.Argument(1) Path relativePath,
                @Advice.Return Optional<?> result,
                @Advice.Enter long start,
                @Advice.Thrown Throwable thrown) {
            try {
                Recorder.loadEnd(parent, relativePath == null ? null : relativePath.toString(),
                        result == null ? null : result.orElse(null), start, thrown);
            } catch (Throwable ignored) {
            }
        }
    }
}
