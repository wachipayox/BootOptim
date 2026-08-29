package dev.wachipayox.bootoptim;

import net.neoforged.fml.common.Mod;

/**
 * BootOptim entry point.
 *
 * Keep this class intentionally minimal: startup instrumentation and optimizations
 * belong in narrowly scoped components so the mod itself does not add avoidable
 * work to the boot path.
 */
@Mod(BootOptim.MOD_ID)
public final class BootOptim {
    public static final String MOD_ID = "boot_optim";

    public BootOptim() {
    }
}
