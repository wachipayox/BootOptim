package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Transparent diagnostic delegate around ModLauncher's already-initialized Mixin launch plugin.
 *
 * <p>Every launch-plugin callback is forwarded unchanged. The sole wrapping point is the
 * {@link ITransformerLoader} supplied during {@link #initializeLaunch}, which records side-load
 * outcomes and timings but never caches, skips, copies, or changes a result.</p>
 */
final class ProfilingMixinLaunchPluginService implements ILaunchPluginService {
    private final ILaunchPluginService delegate;

    ProfilingMixinLaunchPluginService(ILaunchPluginService delegate) {
        this.delegate = delegate;
    }

    ILaunchPluginService delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        return delegate.handlesClass(classType, isEmpty);
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty, String reason) {
        return delegate.handlesClass(classType, isEmpty, reason);
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType) {
        return delegate.processClass(phase, classNode, classType);
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType, String reason) {
        return delegate.processClass(phase, classNode, classType, reason);
    }

    @Override
    public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
        return delegate.processClassWithFlags(phase, classNode, classType, reason);
    }

    @Override
    public void offerResource(Path resource, String name) {
        delegate.offerResource(resource, name);
    }

    @Override
    public void addResources(List<SecureJar> resources) {
        delegate.addResources(resources);
    }

    @Override
    @SuppressWarnings("removal")
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        delegate.initializeLaunch(
                new MixinClassInfoSideLoadProbe(transformerLoader, delegate.getClass().getClassLoader()),
                specialPaths);
    }

    @Override
    public <T> T getExtension() {
        return delegate.getExtension();
    }

    @Override
    public void customAuditConsumer(String className, Consumer<String[]> auditDataAcceptor) {
        delegate.customAuditConsumer(className, auditDataAcceptor);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
