package dev.wachipayox.bootoptim.zipfsagent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java agent (JDK 25 class-file API, no external bytecode library).
 *
 * <p>Adds {@code accessMode=readOnly} to the zip opens that {@link ZipfsReadOnlyPolicy} selects, by prepending one
 * call at the start of {@code jdk.nio.zipfs.ZipFileSystemProvider.getZipFileSystem(Path, Map)}:
 * {@code env = ZipfsReadOnlyPolicy.adjust(path, env)}. Nothing else in the JDK is changed.
 *
 * <p>Wiring: the manifest lists this JAR in {@code Boot-Class-Path}, so the policy class is visible to the
 * boot loader that defines {@code jdk.zipfs}. The module {@code jdk.zipfs} is given read access to the boot
 * unnamed module, because named modules do not read unnamed modules by default.
 *
 * <p>The transformer removes itself as soon as it has patched the provider. While a transformer is registered the
 * JVM posts a ClassFileLoadHook for every class it loads, which makes each class load (and each class served from a
 * JDK 25 AOT cache, whose original bytes are then rebuilt for the hook) more expensive for the whole game.
 */
public final class ZipfsReadOnlyAgent {

    private static final String POLICY = "dev.wachipayox.bootoptim.zipfsagent.ZipfsReadOnlyPolicy";
    private static final String PROVIDER_CLASS = "jdk.nio.zipfs.ZipFileSystemProvider";
    private static final String PROVIDER_INTERNAL = "jdk/nio/zipfs/ZipFileSystemProvider";
    private static final String TARGET_METHOD = "getZipFileSystem";
    private static final String TARGET_DESC = "(Ljava/nio/file/Path;Ljava/util/Map;)Ljdk/nio/zipfs/ZipFileSystem;";
    private static final ClassDesc POLICY_DESC = ClassDesc.of(POLICY);
    private static final MethodTypeDesc ADJUST_DESC = MethodTypeDesc.of(
            ClassDesc.of("java.util.Map"), ClassDesc.of("java.nio.file.Path"), ClassDesc.of("java.util.Map"));

    /** Same sink as the policy: the status file when configured, otherwise stderr. */
    static void status(String line) {
        String file = System.getProperty(ZipfsReadOnlyPolicy.STATUS_PROPERTY);
        if (file != null && !file.isBlank()) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(file), line + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                return;
            } catch (java.io.IOException | RuntimeException ignored) {
                // fall through to stderr
            }
        }
        System.err.println(line);
    }

    /** Calls of the transformer, for the self-test: it must stay at the patched provider once removed. */
    public static final AtomicInteger TRANSFORM_CALLS = new AtomicInteger();

    private ZipfsReadOnlyAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            Class<?> policy = Class.forName(POLICY, false, null);
            Class<?> provider = findProviderClass();
            instrumentation.redefineModule(provider.getModule(), Set.of(policy.getModule()),
                    Map.of(), Map.of(), Set.of(), Map.of());
            ProviderTransformer transformer = new ProviderTransformer(instrumentation);
            instrumentation.addTransformer(transformer, true);
            if (instrumentation.isModifiableClass(provider)) {
                instrumentation.retransformClasses(provider);
            }
            if (!transformer.patched) {
                // Nothing was patched (the provider could not be retransformed): do not keep the hook for nothing.
                instrumentation.removeTransformer(transformer);
            }
        } catch (Throwable failure) {
            status("[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=zipfs-readonly-agent reason=" + failure);
        }
    }

    /** jdk.zipfs is defined by the platform loader in this JDK; the boot loader alone does not see it. */
    private static Class<?> findProviderClass() throws ClassNotFoundException {
        try {
            return Class.forName(PROVIDER_CLASS, false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException notOnPlatform) {
            return Class.forName(PROVIDER_CLASS, false, null);
        }
    }

    static byte[] patchProvider(byte[] classfile) {
        ClassModel model = ClassFile.of().parse(classfile);
        return ClassFile.of().transformClass(model, ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString(TARGET_METHOD)
                        && method.methodType().equalsString(TARGET_DESC),
                CodeTransform.ofStateful(() -> {
                    boolean[] prepended = {false};
                    return (builder, element) -> {
                        if (!prepended[0]) {
                            prepended[0] = true;
                            builder.aload(1)
                                    .aload(2)
                                    .invokestatic(POLICY_DESC, "adjust", ADJUST_DESC)
                                    .astore(2);
                        }
                        builder.with(element);
                    };
                })));
    }

    private static final class ProviderTransformer implements ClassFileTransformer {
        private final Instrumentation instrumentation;
        private volatile boolean patched;

        ProviderTransformer(Instrumentation instrumentation) {
            this.instrumentation = instrumentation;
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            TRANSFORM_CALLS.incrementAndGet();
            if (!PROVIDER_INTERNAL.equals(className)) {
                return null;
            }
            try {
                byte[] result = patchProvider(classfileBuffer);
                // Patched once (at load or by retransformClasses): drop the hook so later class loads are free.
                // A later retransformation of the provider by someone else would start from the original bytes.
                patched = true;
                instrumentation.removeTransformer(this);
                return result;
            } catch (Throwable failure) {
                status("[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=zipfs-readonly-agent reason=" + failure);
                return null;
            }
        }
    }
}
