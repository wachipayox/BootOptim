package dev.wachipayox.bootoptim.bootstrap;

import java.util.concurrent.atomic.AtomicLong;

/** Agent 94 diagnostic-only monotonic boundary for the exact Bootstrap transform and entry. */
public final class MinecraftBootstrapForkProfileHooks {
    private static final AtomicLong ACCEPT_NANOS = new AtomicLong();

    private MinecraftBootstrapForkProfileHooks() {}

    static void transformAccepted() {
        if (!Boolean.getBoolean("boot_optim.modlauncherForkTrace")) return;
        long now = System.nanoTime();
        if (ACCEPT_NANOS.compareAndSet(0L, now)) {
            System.err.printf(
                    "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY event=transform_accept mono_ns=%d thread=%s%n",
                    now, Thread.currentThread().getName());
        }
    }

    public static void entry() {
        if (!Boolean.getBoolean("boot_optim.modlauncherForkTrace")) return;
        long now = System.nanoTime();
        long accept = ACCEPT_NANOS.get();
        long delta = accept == 0L ? -1L : now - accept;
        System.err.printf(
                "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY event=bootstrap_entry mono_ns=%d accept_to_entry_ns=%d thread=%s%n",
                now, delta, Thread.currentThread().getName());
    }
}
