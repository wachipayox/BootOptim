package dev.wachipayox.bootoptim.bgscanprofile;

import static net.bytebuddy.matcher.ElementMatchers.named;
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

/** Diagnostic-only observer for the FML 4.0.43 background content-scan launch/overlap/barrier. */
public final class BackgroundScanProfileAgent {
    private static final String MOD_VALIDATOR = "net.neoforged.fml.loading.moddiscovery.ModValidator";
    private static final String LOADING_MOD_LIST = "net.neoforged.fml.loading.LoadingModList";
    private static final String BACKGROUND_SCAN = "net.neoforged.fml.loading.modscan.BackgroundScanHandler";
    private static final String MOD_FILE = "net.neoforged.fml.loading.moddiscovery.ModFile";
    private static final String MOD_LOADER = "net.neoforged.fml.ModLoader";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/bgscanprofile/Recorder.class",
            "dev/wachipayox/bootoptim/bgscanprofile/Recorder$Event.class"
    };

    private BackgroundScanProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.backgroundScanProfile")) return;
        System.err.println("BOOTOPTIM_BGSCAN_PROFILE_ARMED expected_fml="
                + System.getProperty("boot_optim.backgroundScanProfile.expectedFmlVersion", "4.0.43"));
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_BGSCAN_PROFILE_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, Recorder.class)
                .type(named(MOD_VALIDATOR))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(Stage2Advice.class).on(named("stage2Validation").and(takesArguments(0)))))
                .type(named(LOADING_MOD_LIST))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(AddForScanningAdvice.class).on(named("addForScanning").and(takesArguments(1)))))
                .type(named(BACKGROUND_SCAN))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(SubmitAdvice.class).on(named("submitForScanning").and(takesArguments(1))))
                        .visit(Advice.to(CompletedAdvice.class).on(named("addCompletedFile").and(takesArguments(3))))
                        .visit(Advice.to(WaitAdvice.class).on(named("waitForScanToComplete").and(takesArguments(1)))))
                .type(named(MOD_FILE))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(CompileAdvice.class).on(named("compileContent").and(takesArguments(0))))
                        .visit(Advice.to(SetResultAdvice.class).on(named("setScanResult").and(takesArguments(2))))
                        .visit(Advice.to(GetResultAdvice.class).on(named("getScanResult").and(takesArguments(0)))))
                .type(named(MOD_LOADER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(GatherAdvice.class).on(named("gatherAndInitializeMods").and(takesArguments(3)))))
                .installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-bgscan-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = BackgroundScanProfileAgent.class.getClassLoader();
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

    public static final class Stage2Advice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("stage2_validation", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("stage2_validation", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class AddForScanningAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("add_for_scanning", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("add_for_scanning", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class SubmitAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("submit_for_scanning", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("submit_for_scanning", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class CompletedAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("add_completed_file", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("add_completed_file", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class CompileAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("compile_content", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("compile_content", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class SetResultAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("set_scan_result", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("set_scan_result", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class GetResultAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("get_scan_result", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("get_scan_result", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class WaitAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("scan_barrier_wait", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("scan_barrier_wait", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
    public static final class GatherAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.begin("gather_initialize", owner); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("gather_initialize", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
}
