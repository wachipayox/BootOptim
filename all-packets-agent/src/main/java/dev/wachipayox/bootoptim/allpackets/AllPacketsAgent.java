package dev.wachipayox.bootoptim.allpackets;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Diagnostic-only, version-pinned observer for Create 6.0.10 AllPackets startup work. */
public final class AllPacketsAgent {
    private static final String ENABLED = "boot_optim.allPacketsProfile.enabled";
    private static final String CLINIT_ACTIVE = "boot_optim.allPacketsProfile.clinitActive";
    private static final String EXPECTED_CREATE = "6.0.10";
    private static final String CREATE_SOURCE = "ac0c444d9828da3453ae8cc65338e8de063286fb";

    private AllPacketsAgent() {}

    public static void premain(String ignored, Instrumentation instrumentation) {
        System.setProperty(ENABLED, "false");
        System.setProperty(CLINIT_ACTIVE, "false");
        System.err.println("BOOTOPTIM_ALLPACKETS_PROFILE install expected_create=" + EXPECTED_CREATE
                + " source_pin=" + CREATE_SOURCE);

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .type(named("com.simibubi.create.Create"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(CreateCtorAdvice.class)
                        .on(named("onCtor").and(takesArguments(2)))))
                .type(named("com.simibubi.create.AllPackets"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(AllPacketsClinitAdvice.class).on(isTypeInitializer()))
                        .visit(Advice.to(AllPacketsCtorAdvice.class).on(isConstructor()))
                        .visit(Advice.to(AllPacketsRegisterAdvice.class)
                                .on(named("register").and(takesArguments(0)))))
                .type(nameStartsWith("com.simibubi.create.").and(not(named("com.simibubi.create.AllPackets"))))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(TransitiveClinitAdvice.class)
                        .on(isTypeInitializer())))
                .type(nameStartsWith("net.createmod.catnip."))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(TransitiveClinitAdvice.class)
                        .on(isTypeInitializer())))
                .type(named("net.createmod.catnip.net.base.CatnipPacketRegistry"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(CatnipRegisterPacketAdvice.class)
                                .on(named("registerPacket").and(takesArguments(1))))
                        .visit(Advice.to(CatnipRegisterAllAdvice.class)
                                .on(named("registerAllPackets").and(takesArguments(0)))))
                .type(named("net.createmod.catnip.platform.NeoForgeNetworkHelper"))
                .transform((b, t, cl, m, pd) -> b.visit(Advice.to(NetworkHelperAdvice.class)
                        .on(named("registerPackets").and(takesArguments(1)))))
                .installOn(instrumentation);
    }

    public static final class CreateCtorAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Argument(1) Object modContainer) {
            String version = null;
            try {
                Method getModInfo = modContainer.getClass().getMethod("getModInfo");
                Object info = getModInfo.invoke(modContainer);
                Method getVersion = info.getClass().getMethod("getVersion");
                Object value = getVersion.invoke(info);
                version = value == null ? null : value.toString();
            } catch (Throwable error) {
                System.err.println("BOOTOPTIM_ALLPACKETS_PROFILE_DISABLED reason=create_version_probe_failed error="
                        + error.getClass().getName());
            }
            boolean accepted = EXPECTED_CREATE.equals(version);
            System.setProperty(ENABLED, Boolean.toString(accepted));
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_CREATE_CTOR_BEGIN ns=" + now
                    + " observed_create=" + String.valueOf(version)
                    + " accepted=" + accepted);
            return now;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_CREATE_CTOR_END ns=" + now
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
            System.setProperty(CLINIT_ACTIVE, "false");
            System.setProperty(ENABLED, "false");
        }
    }

    public static final class AllPacketsClinitAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Origin Class<?> owner) {
            if (!Boolean.getBoolean(ENABLED)) return 0L;
            System.setProperty(CLINIT_ACTIVE, "true");
            long now = System.nanoTime();
            Package pkg = owner.getPackage();
            System.err.println("BOOTOPTIM_ALLPACKETS_CLINIT_BEGIN ns=" + now
                    + " package_version=" + (pkg == null ? "null" : String.valueOf(pkg.getImplementationVersion())));
            return now;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_CLINIT_END ns=" + now
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
            System.setProperty(CLINIT_ACTIVE, "false");
        }
    }

    public static final class TransitiveClinitAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Origin Class<?> owner) {
            if (!Boolean.getBoolean(CLINIT_ACTIVE)) return 0L;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_DEP_CLINIT_BEGIN ns=" + now + " class=" + owner.getName());
            return now;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin Class<?> owner, @Advice.Enter long start,
                                @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_DEP_CLINIT_END ns=" + now + " class=" + owner.getName()
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
        }
    }

    public static final class AllPacketsCtorAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            return Boolean.getBoolean(CLINIT_ACTIVE) ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object packet, @Advice.Enter long start,
                                @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            String name;
            try {
                name = ((Enum<?>) packet).name();
            } catch (Throwable ignored) {
                name = "unknown";
            }
            System.err.println("BOOTOPTIM_ALLPACKETS_ENUM_CTOR ns=" + now + " name=" + name
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
        }
    }

    public static final class AllPacketsRegisterAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            if (!Boolean.getBoolean(ENABLED)) return 0L;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_REGISTER_BEGIN ns=" + now);
            return now;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_REGISTER_END ns=" + now
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
        }
    }

    public static final class CatnipRegisterPacketAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            return Boolean.getBoolean(ENABLED) ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_REGISTER_PACKET ns=" + now
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
        }
    }

    public static final class CatnipRegisterAllAdvice {
        @Advice.OnMethodEnter
        public static long enter() {
            if (!Boolean.getBoolean(ENABLED)) return 0L;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_REGISTER_ALL_BEGIN ns=" + now);
            return now;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_REGISTER_ALL_END ns=" + now
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
        }
    }

    public static final class NetworkHelperAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Origin Class<?> owner) {
            if (!Boolean.getBoolean(ENABLED)) return 0L;
            long now = System.nanoTime();
            Package pkg = owner.getPackage();
            System.err.println("BOOTOPTIM_ALLPACKETS_NETWORK_HELPER_BEGIN ns=" + now
                    + " catnip_package_version=" + (pkg == null ? "null" : String.valueOf(pkg.getImplementationVersion())));
            return now;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long start, @Advice.Thrown Throwable thrown) {
            if (start == 0L) return;
            long now = System.nanoTime();
            System.err.println("BOOTOPTIM_ALLPACKETS_NETWORK_HELPER_END ns=" + now
                    + " dur_ns=" + (now - start)
                    + (thrown == null ? "" : " throw=" + thrown.getClass().getName()));
        }
    }
}
