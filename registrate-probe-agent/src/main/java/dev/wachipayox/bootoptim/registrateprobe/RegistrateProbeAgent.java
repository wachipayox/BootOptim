package dev.wachipayox.bootoptim.registrateprobe;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
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

/** Opt-in, diagnostic-only operation probe for Create 6.0.10 AllBlockEntityTypes.<clinit>. */
public final class RegistrateProbeAgent {
    private static final String[] BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/registrateprobe/Recorder.class",
            "dev/wachipayox/bootoptim/registrateprobe/Recorder$State.class",
            "dev/wachipayox/bootoptim/registrateprobe/Recorder$Frame.class",
            "dev/wachipayox/bootoptim/registrateprobe/Recorder$EntryResult.class"
    };

    private RegistrateProbeAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.registrateProbe")) return;
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_REGISTRATE_PROBE_INSTALL_FAILED " + error);
            return;
        }
        Recorder.installShutdownFlush();

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, RegistrateProbeAgent.class, Recorder.class)
                .type(named("com.simibubi.create.AllBlockEntityTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(ClinitAdvice.class)
                        .on(net.bytebuddy.matcher.ElementMatchers.isTypeInitializer())))
                .type(named("com.simibubi.create.foundation.data.CreateRegistrate"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(BlockEntity2Advice.class).on(named("blockEntity").and(takesArguments(2))))
                        .visit(Advice.to(BlockEntity3Advice.class).on(named("blockEntity").and(takesArguments(3))))
                        .visit(Advice.to(RegisterOpAdvice.class).on(named("accept"))))
                .type(named("com.simibubi.create.foundation.data.CreateBlockEntityBuilder"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(CreateBuilderAdvice.class).on(named("create").and(isStatic())))
                        .visit(Advice.to(VisualAdvice.class).on(named("visual")))
                        .visit(Advice.to(ValidAdvice.class).on(named("validBlocksDeferred"))))
                .type(named("com.tterrag.registrate.builders.BlockEntityBuilder"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(ValidAdvice.class).on(named("validBlock").or(named("validBlocks"))))
                        .visit(Advice.to(RendererAdvice.class).on(named("renderer")))
                        .visit(Advice.to(BlockEntityRegisterAdvice.class).on(named("register").and(takesArguments(0)))))
                .type(named("com.tterrag.registrate.util.OneTimeEventReceiver"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(ListenerAdvice.class)
                        .on(named("addModListener").and(isStatic()).and(takesArguments(4)))))
                .installOn(instrumentation);
    }

    private static JarFile createBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-registrate-probe-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = RegistrateProbeAgent.class.getClassLoader();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(bridge))) {
            for (String resource : BRIDGE_CLASSES) {
                try (InputStream input = loader.getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("missing bridge class: " + resource);
                    output.putNextEntry(new JarEntry(resource));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        return new JarFile(bridge.toFile());
    }

    public static final class ClinitAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.clinitBegin(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.clinitEnd(thrown); }
    }
    public static final class BlockEntity2Advice {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) String name) { Recorder.entryBegin(name); Recorder.opEnter("creation"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.opExit("creation", thrown); }
    }
    public static final class BlockEntity3Advice {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(1) String name) { Recorder.entryBegin(name); Recorder.opEnter("creation"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.opExit("creation", thrown); }
    }
    public static final class CreateBuilderAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.opEnter("creation"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.opExit("creation", thrown); }
    }
    public static final class VisualAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.opEnter("visual"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.opExit("visual", thrown); }
    }
    public static final class ValidAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.opEnter("valid_blocks"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Thrown Throwable thrown) { Recorder.observeRegistrateClass(owner); Recorder.opExit("valid_blocks", thrown); }
    }
    public static final class RendererAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.opEnter("renderer"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Thrown Throwable thrown) { Recorder.observeRegistrateClass(owner); Recorder.opExit("renderer", thrown); }
    }
    public static final class RegisterOpAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.opEnter("register_accept"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.opExit("register_accept", thrown); }
    }
    public static final class BlockEntityRegisterAdvice {
        @Advice.OnMethodEnter public static void enter() { Recorder.opEnter("register_accept"); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin Class<?> owner, @Advice.Thrown Throwable thrown) {
            Recorder.observeRegistrateClass(owner);
            long end = Recorder.opExit("register_accept", thrown);
            Recorder.entryEnd(end, thrown);
        }
    }
    public static final class ListenerAdvice {
        @Advice.OnMethodEnter public static long enter() { return Recorder.listenerEnter(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) { Recorder.listenerExit(start, thrown); }
    }
}
