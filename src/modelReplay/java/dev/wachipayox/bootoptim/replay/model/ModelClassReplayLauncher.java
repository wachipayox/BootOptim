package dev.wachipayox.bootoptim.replay.model;

import java.util.Arrays;
import net.minecraft.server.Bootstrap;

/** Minimal headless launcher that establishes Minecraft's registry bootstrap before ModelBakery references. */
public final class ModelClassReplayLauncher {
    private ModelClassReplayLauncher() {}

    public static void main(String[] args) throws Throwable {
        Bootstrap.bootStrap();
        if (args.length > 0 && "--bounded-self-test".equals(args[0])) {
            ModelClassReplaySelfTest.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        ModelClassReplay.main(args);
    }
}
