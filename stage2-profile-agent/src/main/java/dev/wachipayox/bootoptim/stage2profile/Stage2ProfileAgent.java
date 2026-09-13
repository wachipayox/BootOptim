package dev.wachipayox.bootoptim.stage2profile;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
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

/** Diagnostic-only, default-off observer for the FML 4.0.43 Stage-2 pipeline. */
public final class Stage2ProfileAgent {
    private static final String MOD_VALIDATOR = "net.neoforged.fml.loading.moddiscovery.ModValidator";
    private static final String MOD_SORTER = "net.neoforged.fml.loading.ModSorter";
    private static final String LOADING_MOD_LIST = "net.neoforged.fml.loading.LoadingModList";
    private static final String BACKGROUND_SCAN = "net.neoforged.fml.loading.modscan.BackgroundScanHandler";
    private static final String MOD_FILE = "net.neoforged.fml.loading.moddiscovery.ModFile";
    private static final String FML_LOADER = "net.neoforged.fml.loading.FMLLoader";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/stage2profile/Recorder.class",
            "dev/wachipayox/bootoptim/stage2profile/Recorder$Event.class"
    };

    private Stage2ProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.stage2Profile")) return;
        System.err.println("BOOTOPTIM_STAGE2_PROFILE_ARMED expected_fml="
                + System.getProperty("boot_optim.stage2Profile.expectedFmlVersion", "4.0.43"));
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_STAGE2_PROFILE_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, Recorder.class)
                .type(named(FML_LOADER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(CompleteScanAdvice.class).on(named("completeScan").and(takesArguments(2)))))
                .type(named(MOD_VALIDATOR))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(Stage2Advice.class).on(named("stage2Validation").and(takesArguments(0))))
                        .visit(Advice.to(ValidateLanguagesAdvice.class).on(named("validateLanguages").and(takesArguments(0)))))
                .type(named(MOD_SORTER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(SorterAdvice.class).on(named("sort").and(takesArguments(3)))))
                .type(named(LOADING_MOD_LIST))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(AddAccessTransformersAdvice.class).on(named("addAccessTransformers").and(takesArguments(0))))
                        .visit(Advice.to(AddMixinConfigsAdvice.class).on(named("addMixinConfigs").and(takesArguments(0))))
                        .visit(Advice.to(AddEnumExtendersAdvice.class).on(named("addEnumExtenders").and(takesArguments(0))))
                        .visit(Advice.to(AddForScanningAdvice.class).on(named("addForScanning").and(takesArguments(1)))))
                .type(named(BACKGROUND_SCAN))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(BackgroundCtorAdvice.class).on(isConstructor().and(takesArguments(0))))
                        .visit(Advice.to(SetLoadingModListAdvice.class).on(named("setLoadingModList").and(takesArguments(1))))
                        .visit(Advice.to(SubmitForScanningAdvice.class).on(named("submitForScanning").and(takesArguments(1))))
                        .visit(Advice.to(ScanWaitAdvice.class).on(named("waitForScanToComplete").and(takesArguments(1)))))
                .type(named(MOD_FILE))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(CompileContentAdvice.class).on(named("compileContent").and(takesArguments(0)))))
                .installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-stage2-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = Stage2ProfileAgent.class.getClassLoader();
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

    public static final class CompleteScanAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("complete_scan", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("complete_scan", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class Stage2Advice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("stage2_validation", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("stage2_validation", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class ValidateLanguagesAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("validate_languages", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("validate_languages", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class SorterAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("sorter_total", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("sorter_total", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddAccessTransformersAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("add_access_transformers", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_access_transformers", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddMixinConfigsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("add_mixin_configs", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_mixin_configs", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddEnumExtendersAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("add_enum_extenders", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_enum_extenders", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class BackgroundCtorAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("background_scan_ctor", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("background_scan_ctor", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddForScanningAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("add_for_scanning", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_for_scanning", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class SetLoadingModListAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("scan_register_loading_list", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("scan_register_loading_list", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class SubmitForScanningAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("scan_submit", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("scan_submit", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class CompileContentAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("scan_compile_content", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("scan_compile_content", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class ScanWaitAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginFml("scan_wait", owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("scan_wait", owner, state, thrown); } catch (Throwable ignored) {} }
    }
}
