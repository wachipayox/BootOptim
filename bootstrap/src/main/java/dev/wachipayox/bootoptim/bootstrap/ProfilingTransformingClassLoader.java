package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.Environment;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.TransformStore;
import cpw.mods.modlauncher.TransformingClassLoader;
import java.lang.module.Configuration;
import java.util.List;

/**
 * Diagnostic GAME classloader that observes ModLauncher's stock transformation path without caching it.
 */
final class ProfilingTransformingClassLoader extends TransformingClassLoader {
    ProfilingTransformingClassLoader(
            TransformStore transformStore,
            LaunchPluginHandler pluginHandler,
            Environment environment,
            Configuration configuration,
            List<ModuleLayer> parentLayers) {
        super(transformStore, pluginHandler, environment, configuration, parentLayers);
    }

    @Override
    protected byte[] maybeTransformClassBytes(byte[] bytes, String name, String context) {
        long transformStart = System.nanoTime();
        byte[] transformed = super.maybeTransformClassBytes(bytes, name, context);
        long transformNanos = System.nanoTime() - transformStart;

        long profilerStart = System.nanoTime();
        TransformClassProfiler.record(
                name,
                context,
                transformNanos,
                bytes == null ? 0 : bytes.length,
                transformed == null ? 0 : transformed.length,
                0L);
        long profilerNanos = System.nanoTime() - profilerStart;
        TransformClassProfiler.addBookkeepingNanos(profilerNanos);
        return transformed;
    }
}
