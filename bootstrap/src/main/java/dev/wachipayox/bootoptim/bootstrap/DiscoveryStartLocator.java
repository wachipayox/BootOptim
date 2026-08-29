package dev.wachipayox.bootoptim.bootstrap;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/** Runs before every normal root-mod locator and starts the root discovery timer. */
public final class DiscoveryStartLocator implements IModFileCandidateLocator {
    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        DiscoveryProfiler.beginRoot();
    }
}
