package dev.wachipayox.bootoptim.bootstrap;

/** Runs the pinned Connector instrumentation and then relocates its hook into Connector-owned package space. */
public final class ConnectorWarmResidualPatcherEntry {
    public static void main(String[] args) throws Exception {
        ConnectorWarmResidualPatcher.main(args);
        ConnectorWarmResidualRelocator.main(args);
    }

    private ConnectorWarmResidualPatcherEntry() {}
}
