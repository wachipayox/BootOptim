package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.modlauncher.Environment;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.ModuleLayerHandler;
import cpw.mods.modlauncher.TransformStore;
import cpw.mods.modlauncher.TransformingClassLoader;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import sun.misc.Unsafe;

/**
 * Experimental, version-pinned hook that replaces only ModLauncher's GAME classloader factory.
 * If the hook cannot be installed, startup remains entirely stock.
 */
final class TransformCacheClassLoaderInstaller {
    private static final String ENABLE_PROPERTY = "boot_optim.transformedClassCache";
    private static final String EXPECTED_MODLAUNCHER = "11.0.5";
    private static final String HANDLER_NAME = "cpw.mods.modlauncher.TransformationServicesHandler";
    private static final String BRIDGE_NAME = "cpw.mods.modlauncher.BootOptimTransformationServicesHandler";
    private static final String BRIDGE_INTERNAL = BRIDGE_NAME.replace('.', '/');
    private static final String HANDLER_INTERNAL = HANDLER_NAME.replace('.', '/');
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private static volatile TransformStore transformStore;

    private TransformCacheClassLoaderInstaller() {
    }

    static boolean requested() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static boolean installIfRequested() {
        if (!requested()) {
            StartupDiagnostics.optimization("transformed_class_cache", false, "experimental_disabled_by_default");
            return false;
        }
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return transformStore != null;
        }

        String versionEvidence = identifySupportedModLauncher();
        if (versionEvidence == null) {
            StartupDiagnostics.optimization(
                    "transformed_class_cache", false, "unsupported_or_unidentified_modlauncher");
            return false;
        }

        try {
            Launcher launcher = Launcher.INSTANCE;
            if (launcher == null) {
                throw new IllegalStateException("ModLauncher singleton is not initialized");
            }

            Unsafe unsafe = unsafe();
            TransformStore store = (TransformStore) readField(unsafe, launcher, Launcher.class, "transformStore");
            ModuleLayerHandler layerHandler = (ModuleLayerHandler) readField(unsafe, launcher, Launcher.class, "moduleLayerHandler");
            Class<?> handlerClass = Class.forName(HANDLER_NAME, false, Launcher.class.getClassLoader());

            verifyExpectedStructure(handlerClass);
            TransformedClassCache.initialize();
            transformStore = store;

            MethodHandles.Lookup trusted = trustedLookup(unsafe).in(handlerClass);
            Class<?> bridgeClass;
            try {
                bridgeClass = Class.forName(BRIDGE_NAME, false, Launcher.class.getClassLoader());
            } catch (ClassNotFoundException missing) {
                bridgeClass = trusted.defineClass(makeBridgeClass());
            }

            MethodHandle buildHook = MethodHandles.lookup().findStatic(
                    TransformCacheClassLoaderInstaller.class,
                    "buildClassLoader",
                    MethodType.methodType(
                            TransformingClassLoader.class,
                            LaunchPluginHandler.class,
                            Environment.class,
                            ModuleLayerHandler.class));
            trusted.findStaticSetter(bridgeClass, "BOOTOPTIM_BUILD", MethodHandle.class).invoke(buildHook);

            MethodHandle constructor = trusted.findConstructor(
                    bridgeClass,
                    MethodType.methodType(void.class, TransformStore.class, ModuleLayerHandler.class));
            Object replacement = constructor.invoke(store, layerHandler);
            writeField(unsafe, launcher, Launcher.class, "transformationServicesHandler", replacement);

            StartupDiagnostics.optimization("transformed_class_cache", true, "experimental_modlauncher_factory_hook");
            StartupDiagnostics.event("TRANSFORM_CACHE", "installer=active modlauncher=" + versionEvidence);
            return true;
        } catch (Throwable t) {
            transformStore = null;
            StartupDiagnostics.optimization("transformed_class_cache", false, "installer_failed");
            StartupDiagnostics.failure("transformed_class_cache_installer", t);
            System.out.println("BOOTOPTIM_TRANSFORM_CACHE installer=failed type=" + t.getClass().getName());
            return false;
        }
    }

    private static String identifySupportedModLauncher() {
        String implementationVersion = TransformingClassLoader.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && implementationVersion.startsWith(EXPECTED_MODLAUNCHER)) {
            return implementationVersion;
        }

        // ModDev loads ModLauncher from the dependency JAR but the Package object has no manifest
        // implementation version. The CodeSource remains the exact resolved artifact and therefore
        // gives us a second independent, fail-closed version signal for development/CI.
        try {
            var protectionDomain = TransformingClassLoader.class.getProtectionDomain();
            var source = protectionDomain == null ? null : protectionDomain.getCodeSource();
            var location = source == null ? null : source.getLocation();
            String text = location == null ? null : location.toExternalForm();
            if (text != null && (text.contains("/modlauncher/" + EXPECTED_MODLAUNCHER + "/")
                    || text.contains("modlauncher-" + EXPECTED_MODLAUNCHER + ".jar"))) {
                return EXPECTED_MODLAUNCHER + "@codesource";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void verifyExpectedStructure(Class<?> handlerClass) throws ReflectiveOperationException {
        Field transformationServicesHandler = Launcher.class.getDeclaredField("transformationServicesHandler");
        if (!transformationServicesHandler.getType().getName().equals(HANDLER_NAME)) {
            throw new NoSuchFieldException("Unexpected Launcher.transformationServicesHandler type: "
                    + transformationServicesHandler.getType().getName());
        }
        Launcher.class.getDeclaredField("transformStore");
        Launcher.class.getDeclaredField("moduleLayerHandler");

        handlerClass.getDeclaredConstructor(TransformStore.class, ModuleLayerHandler.class);
        Method build = handlerClass.getDeclaredMethod(
                "buildTransformingClassLoader",
                LaunchPluginHandler.class,
                Environment.class,
                ModuleLayerHandler.class);
        if (build.getReturnType() != TransformingClassLoader.class) {
            throw new NoSuchMethodException("Unexpected buildTransformingClassLoader return type");
        }
    }

    /** Called through a MethodHandle from the bridge class defined inside the ModLauncher module. */
    private static TransformingClassLoader buildClassLoader(
            LaunchPluginHandler pluginHandler,
            Environment environment,
            ModuleLayerHandler layerHandler) {
        TransformStore store = transformStore;
        if (store == null) {
            throw new IllegalStateException("Transform store disappeared before GAME layer creation");
        }

        AtomicReference<CachingTransformingClassLoader> created = new AtomicReference<>();
        layerHandler.buildLayer(IModuleLayerManager.Layer.GAME, (configuration, parents) -> {
            CachingTransformingClassLoader loader = new CachingTransformingClassLoader(
                    store, pluginHandler, environment, configuration, parents);
            created.set(loader);
            return loader;
        });

        CachingTransformingClassLoader gameLoader = created.get();
        if (gameLoader == null) {
            throw new IllegalStateException("GAME layer factory did not create a classloader");
        }

        // Mirror TransformationServicesHandler's PLUGIN -> GAME fallback link without touching LayerInfo,
        // whose record type is package-private in ModLauncher.
        try {
            layerHandler.getLayer(IModuleLayerManager.Layer.PLUGIN).ifPresent(pluginLayer -> pluginLayer.modules().stream()
                    .map(Module::getClassLoader)
                    .filter(ModuleClassLoader.class::isInstance)
                    .map(ModuleClassLoader.class::cast)
                    .findFirst()
                    .ifPresent(pluginLoader -> pluginLoader.setFallbackClassLoader(gameLoader)));
        } catch (Throwable t) {
            // The GAME loader itself is already valid. This mirrors a compatibility link and must not
            // turn a successful cache installation into a startup failure.
            StartupDiagnostics.failure("transformed_class_cache_plugin_fallback", t);
        }

        return gameLoader;
    }

    private static byte[] makeBridgeClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                BRIDGE_INTERNAL, null, HANDLER_INTERNAL, null);

        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                        "BOOTOPTIM_BUILD",
                        "Ljava/lang/invoke/MethodHandle;",
                        null,
                        null)
                .visitEnd();

        MethodVisitor ctor = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "(Lcpw/mods/modlauncher/TransformStore;Lcpw/mods/modlauncher/ModuleLayerHandler;)V",
                null,
                null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                HANDLER_INTERNAL,
                "<init>",
                "(Lcpw/mods/modlauncher/TransformStore;Lcpw/mods/modlauncher/ModuleLayerHandler;)V",
                false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        String methodDesc = "(Lcpw/mods/modlauncher/LaunchPluginHandler;"
                + "Lcpw/mods/modlauncher/Environment;"
                + "Lcpw/mods/modlauncher/ModuleLayerHandler;)"
                + "Lcpw/mods/modlauncher/TransformingClassLoader;";
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "buildTransformingClassLoader",
                methodDesc,
                null,
                null);
        method.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitLabel(start);
        method.visitFieldInsn(
                Opcodes.GETSTATIC,
                BRIDGE_INTERNAL,
                "BOOTOPTIM_BUILD",
                "Ljava/lang/invoke/MethodHandle;");
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                methodDesc,
                false);
        method.visitLabel(end);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(handler);
        method.visitVarInsn(Opcodes.ASTORE, 4);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                HANDLER_INTERNAL,
                "buildTransformingClassLoader",
                methodDesc,
                false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static MethodHandles.Lookup trustedLookup(Unsafe unsafe) throws ReflectiveOperationException {
        Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        return (MethodHandles.Lookup) unsafe.getObject(base, offset);
    }

    private static Object readField(Unsafe unsafe, Object owner, Class<?> declaringClass, String name)
            throws ReflectiveOperationException {
        Field field = declaringClass.getDeclaredField(name);
        return unsafe.getObject(owner, unsafe.objectFieldOffset(field));
    }

    private static void writeField(Unsafe unsafe, Object owner, Class<?> declaringClass, String name, Object value)
            throws ReflectiveOperationException {
        Field field = declaringClass.getDeclaredField(name);
        unsafe.putObject(owner, unsafe.objectFieldOffset(field), value);
    }
}
