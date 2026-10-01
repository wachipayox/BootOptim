package dev.wachipayox.bootoptim.modfileprofile;

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

/** Diagnostic-only, default-off observer for FML 4.0.43 ModFile metadata parsing. */
public final class ModFileMetadataProfileAgent {
    private static final String MOD_FILE = "net.neoforged.fml.loading.moddiscovery.ModFile";
    private static final String MOD_FILE_PARSER = "net.neoforged.fml.loading.moddiscovery.ModFileParser";
    private static final String MOD_VALIDATOR = "net.neoforged.fml.loading.moddiscovery.ModValidator";
    private static final String FILE_CONFIG = "com.electronwill.nightconfig.core.file.FileConfig";
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/modfileprofile/Recorder.class",
            "dev/wachipayox/bootoptim/modfileprofile/Recorder$Event.class"
    };

    private ModFileMetadataProfileAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.modFileMetadataProfile")) return;
        System.err.println("BOOTOPTIM_MODFILE_METADATA_PROFILE_ARMED expected_fml="
                + System.getProperty("boot_optim.modFileMetadataProfile.expectedFmlVersion", "4.0.43"));
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_MODFILE_METADATA_PROFILE_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, Recorder.class)
                .type(named(MOD_VALIDATOR))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(Stage1Advice.class).on(named("stage1Validation").and(takesArguments(0)))))
                .type(named(MOD_FILE_PARSER))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ReadModListAdvice.class).on(named("readModList").and(takesArguments(2))))
                        .visit(Advice.to(StandardParserAdvice.class).on(named("modsTomlParser").and(takesArguments(1))))
                        .visit(Advice.to(CopyConfigAdvice.class).on(named("copyConfig").and(takesArguments(1))))
                        .visit(Advice.to(CoreModsAdvice.class).on(named("getCoreMods").and(takesArguments(1))))
                        .visit(Advice.to(MixinConfigsAdvice.class).on(named("getMixinConfigs").and(takesArguments(1))))
                        .visit(Advice.to(AccessTransformersAdvice.class).on(named("getAccessTransformers").and(takesArguments(1)))))
                .type(named(MOD_FILE))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(IdentifyModsAdvice.class).on(named("identifyMods").and(takesArguments(0)))))
                .type(hasSuperType(named(FILE_CONFIG)).and(not(isInterface())))
                .transform((builder, type, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(FileConfigLoadAdvice.class).on(named("load").and(takesArguments(0)))))
                .installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-modfile-metadata-profile-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = ModFileMetadataProfileAgent.class.getClassLoader();
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

    public static final class Stage1Advice {
        @Advice.OnMethodEnter public static Object[] enter(@Advice.Origin Class<?> owner) {
            try {
                String previous = Recorder.enterPhase("stage1", owner);
                return new Object[] { previous, Recorder.beginStage1(owner) };
            } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(
                @Advice.Origin Class<?> owner, @Advice.Enter Object[] state, @Advice.Thrown Throwable thrown) {
            if (state == null) return;
            try { Recorder.endStage1(owner, (long[]) state[1], thrown); } catch (Throwable ignored) {}
            try { Recorder.exitPhase((String) state[0]); } catch (Throwable ignored) {}
        }
    }

    public static final class ReadModListAdvice {
        @Advice.OnMethodEnter public static Object[] enter(@Advice.Origin Class<?> owner, @Advice.Argument(1) Object parser) {
            try { return Recorder.beginRead(owner, parser); } catch (Throwable ignored) { return null; }
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(
                @Advice.Origin Class<?> owner, @Advice.Enter Object[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endRead(owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class StandardParserAdvice {
        @Advice.OnMethodEnter public static long[] enter() { try { return Recorder.beginNested(); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endNested("mods_toml_parser", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class CopyConfigAdvice {
        @Advice.OnMethodEnter public static long[] enter() { try { return Recorder.beginNested(); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endNested("immutable_copy_write_reparse", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class FileConfigLoadAdvice {
        @Advice.OnMethodEnter public static long[] enter() { try { return Recorder.beginNested(); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endNested("file_config_load", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class IdentifyModsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginStageChild(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endStageChild("identify_mods", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class CoreModsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginStageChild(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endStageChild("coremod_checks", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class MixinConfigsAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginStageChild(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endStageChild("mixin_checks", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }

    public static final class AccessTransformersAdvice {
        @Advice.OnMethodEnter public static long[] enter(@Advice.Origin Class<?> owner) { try { return Recorder.beginStageChild(owner); } catch (Throwable ignored) { return null; } }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long[] state, @Advice.Thrown Throwable thrown) {
            try { Recorder.endStageChild("access_transformer_metadata_checks", owner, state, thrown); } catch (Throwable ignored) {}
        }
    }
}
