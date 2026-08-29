package dev.wachipayox.bootoptim.bootstrap;

import java.util.List;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;

/** Runs after every normal dependency locator and closes the dependency discovery timer. */
public final class DependencyDiscoveryEndLocator implements IDependencyLocator {
    @Override
    public int getPriority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        DiscoveryProfiler.endDependencies();
    }
}
