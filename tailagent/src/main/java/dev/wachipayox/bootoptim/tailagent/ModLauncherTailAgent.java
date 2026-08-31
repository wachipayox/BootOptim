package dev.wachipayox.bootoptim.tailagent;

import java.lang.instrument.Instrumentation;

/** Premain entry point for the standalone diagnostic agent. */
public final class ModLauncherTailAgent {
    private ModLauncherTailAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("BOOTOPTIM_MODLAUNCHER_TAIL agent=status_started");
        instrumentation.addTransformer(new TailClassFileTransformer(instrumentation), false);
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> TailRuntime.report("shutdown"),
                "BootOptim ModLauncher Tail Reporter"));
    }
}
