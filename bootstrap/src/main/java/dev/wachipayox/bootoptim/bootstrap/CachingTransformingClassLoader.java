package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.Environment;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.TransformStore;
import cpw.mods.modlauncher.TransformingClassLoader;
import java.lang.module.Configuration;
import java.util.List;

/**
 * Experimental GAME classloader that can reuse already transformed Minecraft bytecode on warm starts.
 * Non-classloading transform requests always stay on ModLauncher's stock path.
 */
final class CachingTransformingClassLoader extends TransformingClassLoader {
    CachingTransformingClassLoader(
            TransformStore transformStore,
            LaunchPluginHandler pluginHandler,
            Environment environment,
            Configuration configuration,
            List<ModuleLayer> parentLayers) {
        super(transformStore, pluginHandler, environment, configuration, parentLayers);
    }

    @Override
    protected byte[] maybeTransformClassBytes(byte[] bytes, String name, String context) {
        byte[] cached = TransformedClassCache.lookup(bytes, name, context);
        if (cached != null) {
            return cached;
        }

        long startedNanos = System.nanoTime();
        byte[] transformed = super.maybeTransformClassBytes(bytes, name, context);
        TransformedClassCache.store(bytes, name, context, transformed, System.nanoTime() - startedNanos);
        return transformed;
    }
}
