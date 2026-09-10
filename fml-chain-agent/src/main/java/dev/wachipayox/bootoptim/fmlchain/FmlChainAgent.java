package dev.wachipayox.bootoptim.fmlchain;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
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

/** Diagnostic-only javaagent for FML construction and exact Create 6.0.10 registration-family attribution. */
public final class FmlChainAgent {
    private static final String[] BOOTSTRAP_BRIDGE_CLASSES = {
            "dev/wachipayox/bootoptim/fmlchain/Recorder.class",
            "dev/wachipayox/bootoptim/fmlchain/Recorder$Event.class"
    };

    private FmlChainAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.fmlChainProfile")) return;

        try {
            instrumentation.appendToBootstrapClassLoaderSearch(createBootstrapBridgeJar());
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_FML_CHAIN_INSTALL_FAILED " + error);
            return;
        }

        Recorder.installShutdownFlush();

        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, FmlChainAgent.class, Recorder.class)
                .type(named("net.neoforged.fml.ModLoader"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(ConstructModsAdvice.class)
                        .on(named("constructMods").and(takesArguments(3)))))
                .type(named("net.neoforged.fml.loading.LoadingModList"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(DependenciesAdvice.class)
                        .on(named("getDependencies").and(takesArguments(1)))))
                .type(named("net.neoforged.fml.javafmlmod.FMLModContainer"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(ConstructAdvice.class)
                        .on(named("constructMod").and(takesArguments(0)))))
                .type(named("net.neoforged.fml.javafmlmod.AutomaticEventSubscriber"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(SubscriberAdvice.class)
                        .on(named("inject").and(takesArguments(3)))))
                .type(named("net.neoforged.fml.ModContainer"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(ConstructEventAdvice.class)
                        .on(named("acceptEvent").and(takesArguments(1)))))
                .type(named("com.simibubi.create.Create"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateCtorAdvice.class)
                        .on(named("onCtor").and(takesArguments(2)))))
                .type(named("com.simibubi.create.foundation.data.CreateRegistrate"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("registerEventListeners").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllSoundEvents"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("prepare").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllCreativeModeTabs"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.content.equipment.armor.AllArmorMaterials"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllDisplaySources"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllDisplayTargets"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllBlocks"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllItems"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllFluids"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.content.decoration.palettes.AllPaletteBlocks"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllMenuTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllEntityTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllBlockEntityTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllRecipeTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllParticleTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllStructureProcessorTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllEntityDataSerializers"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllPackets"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.infrastructure.worldgen.AllFeatures"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.infrastructure.worldgen.AllPlacementModifiers"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.foundation.recipe.AllIngredients"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllAttachmentTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllDataComponents"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllMapDecorationTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(1)))))
                .type(named("com.simibubi.create.AllMountedStorageTypes"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(0)))))
                .type(named("com.simibubi.create.infrastructure.config.AllConfigs"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("register").and(takesArguments(2)).and(isPublic()))))
                .type(named("com.simibubi.create.AllSchematicStateFilters"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("registerDefaults").and(takesArguments(0)))))
                .type(named("com.simibubi.create.AllBogeyStyles"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("init").and(takesArguments(0)))))
                .type(named("net.neoforged.neoforge.common.NeoForgeMod"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateBoundaryAdvice.class)
                        .on(named("enableMilkFluid").and(takesArguments(0)))));

        builder.installOn(instrumentation);
    }

    private static JarFile createBootstrapBridgeJar() throws IOException {
        Path bridge = Files.createTempFile("bootoptim-fml-chain-bootstrap-", ".jar");
        bridge.toFile().deleteOnExit();
        ClassLoader loader = FmlChainAgent.class.getClassLoader();
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

    public static final class ConstructModsAdvice {
        @Advice.OnMethodEnter public static void enter(@Advice.Origin Class<?> owner) { Recorder.beginGate(owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.endGate(thrown); }
    }
    public static final class DependenciesAdvice {
        @Advice.OnMethodExit public static void exit(@Advice.Argument(0) Object modInfo, @Advice.Return Object result) { Recorder.dependency(modInfo, result); }
    }
    public static final class ConstructAdvice {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object container) { Recorder.constructBegin(container); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.This Object container, @Advice.Thrown Throwable thrown) { Recorder.constructEnd(container, thrown); }
    }
    public static final class SubscriberAdvice {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) Object container) { Recorder.subscriberBegin(container); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Argument(0) Object container, @Advice.Thrown Throwable thrown) { Recorder.subscriberEnd(container, thrown); }
    }
    public static final class ConstructEventAdvice {
        @Advice.OnMethodEnter public static boolean enter(@Advice.This Object container, @Advice.Argument(0) Object event) { return Recorder.constructEventBegin(container, event); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.This Object container, @Advice.Enter boolean recorded, @Advice.Thrown Throwable thrown) { Recorder.constructEventEnd(container, recorded, thrown); }
    }
    public static final class CreateCtorAdvice {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(1) Object modContainer, @Advice.Origin Class<?> owner) { Recorder.createCtorBegin(modContainer, owner); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Thrown Throwable thrown) { Recorder.createCtorEnd(thrown); }
    }
    public static final class CreateBoundaryAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Origin("#t.#m") String origin, @Advice.Thrown Throwable thrown) { Recorder.createBoundary(origin, thrown); }
    }
}
