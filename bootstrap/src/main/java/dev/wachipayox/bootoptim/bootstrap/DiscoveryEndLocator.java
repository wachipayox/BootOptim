package dev.wachipayox.bootoptim.bootstrap;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/** Runs after every normal root-mod locator and closes the root discovery timer. */
public final class DiscoveryEndLocator implements IModFileCandidateLocator {
    @Override
    public int getPriority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        DiscoveryProfiler.endRoot();
    }
}
