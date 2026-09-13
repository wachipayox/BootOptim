package dev.wachipayox.bootoptim.fmllangprofile;

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

/** Version-pinned diagnostic observer for FML 4.0.43 Stage 2 language/post-sort boundaries. */
public final class FmlLanguageProfileAgent {
    private static final String MOD_VALIDATOR = "net.neoforged.fml.loading.moddiscovery.ModValidator";
    private static final String LANGUAGE_PROVIDER = "net.neoforged.fml.loading.LanguageProviderLoader";
    private static final String LOADING_MOD_LIST = "net.neoforged.fml.loading.LoadingModList";
    private static final String RECORDER = "dev/wachipayox/bootoptim/fmllangprofile/Recorder.class";
    private static final String EVENT = "dev/wachipayox/bootoptim/fmllangprofile/Recorder$Event.class";

    private FmlLanguageProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.fmlLanguageProfile")) return;
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBridgeJar());
        } catch (Throwable t) {
            System.err.println("BOOTOPTIM_FML_LANGUAGE_PROFILE_INSTALL_FAILED " + t);
            return;
        }
        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, Recorder.class)
                .type(named(MOD_VALIDATOR))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(Stage2Advice.class).on(named("stage2Validation").and(takesArguments(0))))
                        .visit(Advice.to(ValidateLanguagesAdvice.class).on(named("validateLanguages").and(takesArguments(0)))))
                .type(named(LANGUAGE_PROVIDER))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(FindLanguageAdvice.class).on(named("findLanguage").and(takesArguments(3)))))
                .type(named(LOADING_MOD_LIST))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(AddAccessTransformersAdvice.class).on(named("addAccessTransformers").and(takesArguments(0))))
                        .visit(Advice.to(AddMixinConfigsAdvice.class).on(named("addMixinConfigs").and(takesArguments(0))))
                        .visit(Advice.to(AddEnumExtendersAdvice.class).on(named("addEnumExtenders").and(takesArguments(0))))
                        .visit(Advice.to(AddForScanningAdvice.class).on(named("addForScanning").and(takesArguments(1)))))
                .installOn(instrumentation);
    }

    private static JarFile createBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-fml-language-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader cl = FmlLanguageProfileAgent.class.getClassLoader();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(bridge))) {
            for (String resource : new String[] {RECORDER, EVENT}) {
                try (InputStream in = cl.getResourceAsStream(resource)) {
                    if (in == null) throw new IOException("missing bridge class " + resource);
                    out.putNextEntry(new JarEntry(resource));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        return new JarFile(bridge.toFile());
    }

    public static final class Stage2Advice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("stage2_validation", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class ValidateLanguagesAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("validate_languages", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class FindLanguageAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("find_language", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddAccessTransformersAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_access_transformers", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddMixinConfigsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_mixin_configs", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddEnumExtendersAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_enum_extenders", owner, state, thrown); } catch (Throwable ignored) {} }
    }
    public static final class AddForScanningAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.begin(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) { try { Recorder.end("add_for_scanning", owner, state, thrown); } catch (Throwable ignored) {} }
    }
}
