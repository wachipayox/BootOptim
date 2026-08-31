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
 * Timing-only delegate around an already-initialized launch plugin.
 *
 * <p>This wrapper is installed only after all transformation services have completed initialize(), so services such
 * as Mixin which require and retain their concrete launch-plugin implementation have already captured the original
 * object. Every callback is delegated to that exact object.</p>
 */
final class ProfilingLaunchPluginService implements ILaunchPluginService {
    private final ILaunchPluginService delegate;
    private final String pluginName;

    ProfilingLaunchPluginService(ILaunchPluginService delegate) {
        this.delegate = delegate;
        this.pluginName = delegate.name();
    }

    ILaunchPluginService delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return pluginName;
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        long started = System.nanoTime();
        try {
            return delegate.handlesClass(classType, isEmpty);
        } finally {
            record("handles_legacy", classType, null, null, started);
        }
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty, String reason) {
        long started = System.nanoTime();
        try {
            return delegate.handlesClass(classType, isEmpty, reason);
        } finally {
            record("handles", classType, reason, null, started);
        }
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType) {
        long started = System.nanoTime();
        try {
            return delegate.processClass(phase, classNode, classType);
        } finally {
            record("process_legacy", classType, null, phase, started);
        }
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType, String reason) {
        long started = System.nanoTime();
        try {
            return delegate.processClass(phase, classNode, classType, reason);
        } finally {
            record("process", classType, reason, phase, started);
        }
    }

    @Override
    public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
        long started = System.nanoTime();
        try {
            return delegate.processClassWithFlags(phase, classNode, classType, reason);
        } finally {
            record("process_flags", classType, reason, phase, started);
        }
    }

    @Override
    public void offerResource(Path resource, String name) {
        long started = System.nanoTime();
        try {
            delegate.offerResource(resource, name);
        } finally {
            LaunchPluginProfiler.record(
                    pluginName, "offer_resource", null, name, null,
                    System.nanoTime() - started, TransformClassProfiler.currentDepth());
        }
    }

    @Override
    public void addResources(List<SecureJar> resources) {
        long started = System.nanoTime();
        try {
            delegate.addResources(resources);
        } finally {
            LaunchPluginProfiler.record(
                    pluginName, "add_resources", null, "count=" + resources.size(), null,
                    System.nanoTime() - started, TransformClassProfiler.currentDepth());
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        long started = System.nanoTime();
        try {
            delegate.initializeLaunch(transformerLoader, specialPaths);
        } finally {
            LaunchPluginProfiler.record(
                    pluginName, "initialize_launch", null, null, null,
                    System.nanoTime() - started, TransformClassProfiler.currentDepth());
        }
    }

    @Override
    public <T> T getExtension() {
        return delegate.getExtension();
    }

    @Override
    public void customAuditConsumer(String className, Consumer<String[]> auditDataAcceptor) {
        long started = System.nanoTime();
        try {
            delegate.customAuditConsumer(className, auditDataAcceptor);
        } finally {
            LaunchPluginProfiler.record(
                    pluginName, "audit_consumer", className, null, null,
                    System.nanoTime() - started, TransformClassProfiler.currentDepth());
        }
    }

    private void record(String operation, Type classType, String reason, Phase phase, long started) {
        LaunchPluginProfiler.record(
                pluginName,
                operation,
                classType == null ? null : classType.getClassName(),
                reason,
                phase == null ? null : phase.name().toLowerCase(java.util.Locale.ROOT),
                System.nanoTime() - started,
                TransformClassProfiler.currentDepth());
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
