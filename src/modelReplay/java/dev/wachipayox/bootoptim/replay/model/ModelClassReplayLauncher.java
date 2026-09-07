package dev.wachipayox.bootoptim.replay.model;

import net.minecraft.server.Bootstrap;

/** Reproduces the clean-JavaExec FML bootstrap boundary without fabricating LoadingModList state. */
public final class ModelClassReplayLauncher {
    private ModelClassReplayLauncher() {}

    public static void main(String[] args) throws Throwable {
        try {
            Bootstrap.bootStrap();
        } catch (Throwable throwable) {
            boolean featureFlagLoader = false;
            for (StackTraceElement frame : throwable.getStackTrace()) {
                if (frame.getClassName().equals("net.neoforged.neoforge.common.util.flag.FeatureFlagLoader")) {
                    featureFlagLoader = true;
                    break;
                }
            }
            Throwable root = throwable;
            while (root.getCause() != null) root = root.getCause();
            if (featureFlagLoader && root instanceof NullPointerException
                    && String.valueOf(root.getMessage()).contains("LoadingModList")) {
                System.out.println("MODEL_CLASS_HEADLESS_BLOCKED boundary=Bootstrap.bootStrap blocker=FeatureFlagLoader/LoadingModList root=" + root.getClass().getName());
                return;
            }
            throw throwable;
        }
        throw new AssertionError("Expected clean JavaExec NeoForge bootstrap to require FML LoadingModList, but Bootstrap.bootStrap() succeeded");
    }
}
