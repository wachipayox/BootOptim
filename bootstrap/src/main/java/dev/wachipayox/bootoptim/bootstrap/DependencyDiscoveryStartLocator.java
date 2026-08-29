package dev.wachipayox.bootoptim.bootstrap;

import java.util.List;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;

/** Runs before every normal dependency locator and starts the dependency discovery timer. */
public final class DependencyDiscoveryStartLocator implements IDependencyLocator {
    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        DiscoveryProfiler.beginDependencies();
    }
}
