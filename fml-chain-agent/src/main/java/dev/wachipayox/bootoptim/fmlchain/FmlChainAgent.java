package dev.wachipayox.bootoptim.fmlchain;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Diagnostic-only javaagent for NeoForge/FML 4.0.43 mod construction.
 *
 * <p>The agent adds timing advice only. It does not replace executors, futures, dependency edges,
 * active-container scope, callbacks, or failure propagation. Events are buffered in memory and
 * written after JVM shutdown so the construction gate performs no file I/O.</p>
 */
public final class FmlChainAgent {
    private FmlChainAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("boot_optim.fmlChainProfile")) return;

        Recorder.installShutdownFlush();

        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .assureReadEdgeTo(instrumentation, FmlChainAgent.class, Recorder.class)
                .type(named("net.neoforged.fml.ModLoader"))
                .transform((dynamicBuilder, type, classLoader, module, protectionDomain) ->
                        dynamicBuilder.visit(Advice.to(ConstructModsAdvice.class)
                                .on(named("constructMods").and(takesArguments(3)))))
                .type(named("net.neoforged.fml.loading.LoadingModList"))
                .transform((dynamicBuilder, type, classLoader, module, protectionDomain) ->
                        dynamicBuilder.visit(Advice.to(DependenciesAdvice.class)
                                .on(named("getDependencies").and(takesArguments(1)))))
                .type(named("net.neoforged.fml.javafmlmod.FMLModContainer"))
                .transform((dynamicBuilder, type, classLoader, module, protectionDomain) ->
                        dynamicBuilder.visit(Advice.to(ConstructAdvice.class)
                                .on(named("constructMod").and(takesArguments(0)))))
                .type(named("net.neoforged.fml.javafmlmod.AutomaticEventSubscriber"))
                .transform((dynamicBuilder, type, classLoader, module, protectionDomain) ->
                        dynamicBuilder.visit(Advice.to(SubscriberAdvice.class)
                                .on(named("inject").and(takesArguments(3)))))
                .type(named("net.neoforged.fml.ModContainer"))
                .transform((dynamicBuilder, type, classLoader, module, protectionDomain) ->
                        dynamicBuilder.visit(Advice.to(ConstructEventAdvice.class)
                                .on(named("acceptEvent").and(takesArguments(1)))));

        builder.installOn(instrumentation);
    }

    public static final class ConstructModsAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin Class<?> owner) {
            Recorder.beginGate(owner);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable thrown) {
            Recorder.endGate(thrown);
        }
    }

    public static final class DependenciesAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.Argument(0) Object modInfo, @Advice.Return Object result) {
            Recorder.dependency(modInfo, result);
        }
    }

    public static final class ConstructAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object container) {
            Recorder.constructBegin(container);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object container, @Advice.Thrown Throwable thrown) {
            Recorder.constructEnd(container, thrown);
        }
    }

    public static final class SubscriberAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(0) Object container) {
            Recorder.subscriberBegin(container);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) Object container, @Advice.Thrown Throwable thrown) {
            Recorder.subscriberEnd(container, thrown);
        }
    }

    public static final class ConstructEventAdvice {
        @Advice.OnMethodEnter
        public static boolean enter(@Advice.This Object container, @Advice.Argument(0) Object event) {
            return Recorder.constructEventBegin(container, event);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This Object container,
                @Advice.Enter boolean recorded,
                @Advice.Thrown Throwable thrown) {
            Recorder.constructEventEnd(container, recorded, thrown);
        }
    }
}
