package dev.wachipayox.bootoptim.fmlregprofile;

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

/** Diagnostic-only observer for three FML 4.0.43 LoadingModList publication paths. */
public final class FmlRegistrationProfileAgent {
    private static final String LOADING_MOD_LIST = "net.neoforged.fml.loading.LoadingModList";
    private static final String FML_LOADER = "net.neoforged.fml.loading.FMLLoader";
    private static final String DEFERRED_MIXIN = "net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration";
    private static final String ENUM_EXTENDER = "net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender";
    private static final String ENUM_PROTOTYPE = "net.neoforged.fml.common.asm.enumextension.EnumPrototype";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/fmlregprofile/Recorder.class",
            "dev/wachipayox/bootoptim/fmlregprofile/Recorder$Event.class"
    };

    private FmlRegistrationProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.fmlRegistrationProfile")) return;
        System.err.println("BOOTOPTIM_FMLREG_PROFILE_ARMED expected_fml="
                + System.getProperty("boot_optim.fmlRegistrationProfile.expectedFmlVersion", "4.0.43"));
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_FMLREG_PROFILE_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, Recorder.class)
                .type(named(LOADING_MOD_LIST))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(AddATsAdvice.class).on(named("addAccessTransformers").and(takesArguments(0))))
                        .visit(Advice.to(AddMixinsAdvice.class).on(named("addMixinConfigs").and(takesArguments(0))))
                        .visit(Advice.to(AddEnumsAdvice.class).on(named("addEnumExtenders").and(takesArguments(0)))))
                .type(named(FML_LOADER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(AddATFileAdvice.class).on(named("addAccessTransformer").and(takesArguments(2)))))
                .type(named(DEFERRED_MIXIN))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(QueueMixinAdvice.class).on(named("addMixinConfig").and(takesArguments(String.class, String.class)))))
                .type(named(ENUM_EXTENDER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(LoadEnumPrototypesAdvice.class).on(named("loadEnumPrototypes"))))
                .type(named(ENUM_PROTOTYPE))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(LoadEnumFileAdvice.class).on(named("load").and(takesArguments(2)))))
                .installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-fmlreg-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = FmlRegistrationProfileAgent.class.getClassLoader();
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

    public static final class AddATsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("add_access_transformers", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("add_access_transformers", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class AddMixinsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("add_mixin_configs", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("add_mixin_configs", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class AddEnumsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("add_enum_extenders", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("add_enum_extenders", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class AddATFileAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("access_transformer_file", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("access_transformer_file", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class QueueMixinAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("mixin_config_queue", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("mixin_config_queue", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class LoadEnumPrototypesAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("enum_prototypes_total", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("enum_prototypes_total", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class LoadEnumFileAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) {
            try { return Recorder.beginFml("enum_prototype_file", owner, null); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.end("enum_prototype_file", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
}
