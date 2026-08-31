package dev.wachipayox.bootoptim.tailagent;

import java.lang.instrument.Instrumentation;

/** Premain entry point for the standalone diagnostic agent. */
public final class ModLauncherTailAgent {
    private ModLauncherTailAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("BOOTOPTIM_MODLAUNCHER_TAIL agent=status_started");
        instrumentation.addTransformer(new TailClassFileTransformer(instrumentation), false);
        // The shutdown reporter is registered by the helper after it is defined directly inside
        // Mixin's module/classloader. Keeping runtime state there avoids cross-loader calls from
        // instrumented ModLauncher classes back into the javaagent loader.
    }
}
