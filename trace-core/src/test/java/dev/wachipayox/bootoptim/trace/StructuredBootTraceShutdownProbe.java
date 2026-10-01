package dev.wachipayox.bootoptim.trace;

import java.nio.file.Path;

public final class StructuredBootTraceShutdownProbe {
    private StructuredBootTraceShutdownProbe() {}

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("trace path required");
        System.setProperty("boot_optim.bootTrace.mode", "profile");
        System.setProperty("boot_optim.bootTrace.path", Path.of(args[0]).toAbsolutePath().toString());
        System.setProperty("boot_optim.bootTrace.origin", "shutdown_probe");
        System.setProperty("boot_optim.bootTrace.endpoint", "process_exit");
        StructuredBootTrace.global().record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, "probe");
    }
}
