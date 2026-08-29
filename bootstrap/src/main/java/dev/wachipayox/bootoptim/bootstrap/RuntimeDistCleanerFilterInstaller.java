package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.asm.RuntimeDistCleaner;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Benchmark-gated replacement for FML's RuntimeDistCleaner launch plugin.
 *
 * <p>ModLauncher 11 keeps its plugin map private and does not expose a replacement API. The experiment therefore
 * uses Unsafe only to obtain the already-created mutable plugin map; it never writes VM/object fields. Failure at
 * any point is fail-open and leaves the stock plugin installed.
 */
final class RuntimeDistCleanerFilterInstaller {
    private static final String ENABLE_PROPERTY = "boot_optim.distCleanerFilter";
    private static final String PLUGIN_NAME = "runtimedistcleaner";

    private RuntimeDistCleanerFilterInstaller() {}

    static void install(IEnvironment environment) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }

        try {
            ILaunchPluginService current = environment.findLaunchPlugin(PLUGIN_NAME).orElse(null);
            if (current instanceof FilteringRuntimeDistCleaner) {
                return;
            }
            if (!(current instanceof RuntimeDistCleaner cleaner)) {
                System.out.printf(
                        "BOOTOPTIM_DIST_FILTER installed=false reason=unexpected_plugin type=%s%n",
                        current == null ? "missing" : current.getClass().getName());
                return;
            }

            Object launchPluginHandler = readPrivateField(Launcher.INSTANCE, "launchPlugins");
            Object rawPlugins = readPrivateField(launchPluginHandler, "plugins");
            if (!(rawPlugins instanceof Map<?, ?> rawMap)) {
                System.out.println("BOOTOPTIM_DIST_FILTER installed=false reason=plugin_map_missing");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, ILaunchPluginService> plugins = (Map<String, ILaunchPluginService>) rawMap;
            FilteringRuntimeDistCleaner replacement = new FilteringRuntimeDistCleaner(cleaner);
            boolean replaced = plugins.replace(PLUGIN_NAME, cleaner, replacement);
            System.out.printf(
                    "BOOTOPTIM_DIST_FILTER installed=%s mechanism=unsafe_map_access%n",
                    replaced);
        } catch (Throwable throwable) {
            System.out.printf(
                    "BOOTOPTIM_DIST_FILTER installed=false reason=%s%n",
                    throwable.getClass().getSimpleName());
        }
    }

    private static Object readPrivateField(Object target, String fieldName) throws Exception {
        if (target == null) {
            throw new IllegalStateException("Missing target for " + fieldName);
        }

        Field targetField = target.getClass().getDeclaredField(fieldName);

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singletonField = unsafeClass.getDeclaredField("theUnsafe");
        singletonField.setAccessible(true);
        Object unsafe = singletonField.get(null);
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        Method getObject = unsafeClass.getMethod("getObject", Object.class, long.class);
        long offset = ((Number) objectFieldOffset.invoke(unsafe, targetField)).longValue();
        return getObject.invoke(unsafe, target, offset);
    }

    private static final class FilteringRuntimeDistCleaner extends RuntimeDistCleaner {
        private static final EnumSet<Phase> NONE = EnumSet.noneOf(Phase.class);
        private final RuntimeDistCleaner delegate;

        private FilteringRuntimeDistCleaner(RuntimeDistCleaner delegate) {
            this.delegate = delegate;
        }

        @Override
        public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
            if (!isEmpty && DistCleanerClassIndex.canSkip(classType.getInternalName())) {
                return NONE;
            }
            return delegate.handlesClass(classType, isEmpty);
        }

        @Override
        public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty, String reason) {
            if (!isEmpty && DistCleanerClassIndex.canSkip(classType.getInternalName())) {
                return NONE;
            }
            return delegate.handlesClass(classType, isEmpty, reason);
        }

        @Override
        public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
            return delegate.processClassWithFlags(phase, classNode, classType, reason);
        }

        @Override
        public void setDistribution(Dist dist) {
            super.setDistribution(dist);
            delegate.setDistribution(dist);
        }

        @Override
        public void offerResource(Path resource, String name) {
            delegate.offerResource(resource, name);
        }

        @Override
        public void addResources(List<SecureJar> resources) {
            delegate.addResources(resources);
            DistCleanerClassIndex.prepareFromFmlScan();
        }

        @Override
        public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
            delegate.initializeLaunch(transformerLoader, specialPaths);
        }

        @Override
        public <T> T getExtension() {
            return delegate.getExtension();
        }

        @Override
        public void customAuditConsumer(String className, Consumer<String[]> auditDataAcceptor) {
            delegate.customAuditConsumer(className, auditDataAcceptor);
        }
    }
}
