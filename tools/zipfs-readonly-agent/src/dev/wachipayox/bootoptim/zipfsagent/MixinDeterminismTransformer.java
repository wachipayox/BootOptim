package dev.wachipayox.bootoptim.zipfsagent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replaces the two {@code UUID.randomUUID()} calls in Mixin that name generated code, so that Mixin-transformed
 * classes have the same bytes on every launch (a JDK 25 AOT cache can then serve them).
 *
 * <p>Only the exact expected call is replaced: {@code MixinProcessor.<init>} must contain exactly one
 * {@code randomUUID} call and {@code InnerClassGenerator.getUniqueReference(String, ClassInfo)} exactly one. If the
 * class or the call is not what is expected, the class is left untouched and a {@code [WBOMBO-COMPAT] FALLBACK}
 * line is written. The JVM compares class bytes with the AOT cache before using a cached class, so a stale cache
 * cannot serve a different class.
 *
 * <p>The transformer removes itself once both classes were seen (patched or rejected).
 */
final class MixinDeterminismTransformer implements ClassFileTransformer {

    static final String MECHANISM = "mixin-deterministic-names";
    private static final String PROCESSOR = "org/spongepowered/asm/mixin/transformer/MixinProcessor";
    private static final String INNER_GEN = "org/spongepowered/asm/mixin/transformer/InnerClassGenerator";
    private static final ClassDesc NAMES = ClassDesc.of("dev.wachipayox.bootoptim.zipfsagent.MixinNames");
    private static final ClassDesc UUID_DESC = ClassDesc.of("java.util.UUID");
    private static final MethodTypeDesc SESSION_DESC = MethodTypeDesc.of(UUID_DESC);
    private static final MethodTypeDesc ANON_DESC = MethodTypeDesc.of(UUID_DESC,
            ClassDesc.of("java.lang.String"), ClassDesc.of("java.lang.Object"));

    /** Class loads seen by the hook, for the self-test. */
    static final AtomicInteger TRANSFORM_CALLS = new AtomicInteger();

    /** Mixin loads within seconds of the start; if it never does, stop paying for the class-load hook. */
    private static final long GIVE_UP_NANOS = 120_000_000_000L;

    private final long createdNanos = System.nanoTime();
    private final Instrumentation instrumentation;
    private final AtomicInteger seen = new AtomicInteger();
    private volatile boolean removed;

    MixinDeterminismTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            return transformSafely(className, classBeingRedefined, loader, classfileBuffer);
        } catch (Throwable failure) {
            // Nothing thrown here may reach the class loader: leave the class as it is.
            return null;
        }
    }

    private byte[] transformSafely(String className, Class<?> classBeingRedefined, ClassLoader loader,
                                   byte[] classfileBuffer) {
        TRANSFORM_CALLS.incrementAndGet();
        if (className == null || classBeingRedefined != null) {
            return null;
        }
        if (!removed && System.nanoTime() - createdNanos > GIVE_UP_NANOS && seen.get() == 0) {
            removed = true;
            instrumentation.removeTransformer(this);
            return null;
        }
        boolean processor = PROCESSOR.equals(className);
        if (!processor && !INNER_GEN.equals(className)) {
            return null;
        }
        byte[] result = null;
        String failure = null;
        try {
            // The patched code calls MixinNames; it must be reachable from every loader (boot class path).
            Class.forName("dev.wachipayox.bootoptim.zipfsagent.MixinNames", false, null);
            result = patch(classfileBuffer, loader, processor);
        } catch (Throwable error) {
            result = null;
            failure = error.toString();
        }
        try {
            if (failure == null) {
                ZipfsReadOnlyAgent.status("[WBOMBO-COMPAT] ACTIVE project=BootOptim mechanism=" + MECHANISM
                        + " mod=mixin class=" + className);
            } else {
                ZipfsReadOnlyAgent.status("[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=" + MECHANISM
                        + " mod=mixin class=" + className + " reason=" + failure);
            }
        } catch (Throwable ignored) {
            // logging must not change the outcome
        }
        try {
            if (seen.incrementAndGet() >= 2 && !removed) {
                removed = true;
                instrumentation.removeTransformer(this);
            }
        } catch (Throwable ignored) {
            // staying registered only costs a cheap name check per class load
        }
        return result;
    }

    static byte[] patch(byte[] classfile, ClassLoader loader, boolean processor) {
        ClassLoader resolverLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(resolverLoader))));
        ClassModel model = cf.parse(classfile);
        int[] replaced = {0};
        byte[] out = cf.transformClass(model, ClassTransform.transformingMethodBodies(method -> {
            if (processor) {
                return method.methodName().equalsString("<init>");
            }
            return method.methodName().equalsString("getUniqueReference")
                    && method.methodType().equalsString("(Ljava/lang/String;Lorg/spongepowered/asm/mixin/transformer/ClassInfo;)Ljava/lang/String;");
        }, CodeTransform.ofStateful(() -> (CodeBuilder builder, CodeElement element) -> {
            if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals("java/util/UUID")
                    && invoke.name().equalsString("randomUUID")
                    && invoke.type().equalsString("()Ljava/util/UUID;")) {
                replaced[0]++;
                if (processor) {
                    builder.invokestatic(NAMES, "sessionUuid", SESSION_DESC);
                } else {
                    builder.aload(0).aload(1).invokestatic(NAMES, "anonymousUuid", ANON_DESC);
                }
            } else {
                builder.with(element);
            }
        })));
        if (replaced[0] != 1) {
            throw new IllegalStateException("expected exactly 1 UUID.randomUUID() call, found " + replaced[0]);
        }
        return out;
    }
}
