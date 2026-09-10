package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Diagnostic-only, fail-closed matcher for NeoForge 1.21.1's patched client Main handoff.
 *
 * <p>Agent 98 adds only serial callsite boundaries in the already-transformed Main method. It does
 * not wrap executors, futures, FML callbacks, or Mixin, and it does not alter the BackgroundWaiter
 * handoff. The topology is required to contain exactly one version detection, DataFixers optimize,
 * CrashReport preload, and BackgroundWaiter call in that order before any mutation is applied.</p>
 */
public final class MinecraftMainLifecycleTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/client/main/Main";
    private static final String BACKGROUND_WAITER = "net/neoforged/fml/loading/BackgroundWaiter";
    private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
    private static final String SHARED_CONSTANTS = "net/minecraft/SharedConstants";
    private static final String DATA_FIXERS = "net/minecraft/util/datafix/DataFixers";
    private static final String CRASH_REPORT = "net/minecraft/CrashReport";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftMainLifecycleHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodNode main = null;
        MethodNode worker = null;
        for (MethodNode method : input.methods) {
            if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) {
                if (main != null) return input;
                main = method;
            }
            if ("lambda$main$0".equals(method.name) && containsBootstrapCall(method)) {
                if (worker != null) return input;
                worker = method;
            }
        }
        if (main == null || worker == null) return input;

        AbstractInsnNode mainFirst = firstExecutable(main);
        AbstractInsnNode workerFirst = firstExecutable(worker);
        if (mainFirst == null || workerFirst == null) return input;

        MethodInsnNode sharedConstants = uniqueCall(main, SHARED_CONSTANTS, "tryDetectVersion", "()V");
        MethodInsnNode dataFixers = uniqueCall(main, DATA_FIXERS, "optimize", null);
        MethodInsnNode crashReport = uniqueCall(main, CRASH_REPORT, "preload", "()V");
        MethodInsnNode runAndTick = uniqueCall(main, BACKGROUND_WAITER, "runAndTick", null);
        if (sharedConstants == null || dataFixers == null || crashReport == null || runAndTick == null) return input;

        List<AbstractInsnNode> original = List.of(main.instructions.toArray());
        int sharedIndex = original.indexOf(sharedConstants);
        int dataFixersIndex = original.indexOf(dataFixers);
        int crashIndex = original.indexOf(crashReport);
        int waiterIndex = original.indexOf(runAndTick);
        if (!(sharedIndex >= 0 && sharedIndex < dataFixersIndex && dataFixersIndex < crashIndex && crashIndex < waiterIndex)) {
            return input;
        }

        List<AbstractInsnNode> workerReturns = new ArrayList<>();
        for (AbstractInsnNode instruction : worker.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.IRETURN
                    || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN) {
                workerReturns.add(instruction);
            }
        }
        if (workerReturns.size() != 1) return input;

        main.instructions.insertBefore(mainFirst, hook("mainEntry"));
        surround(main, sharedConstants, "beforeSharedConstantsVersion", "afterSharedConstantsVersion");
        surround(main, dataFixers, "beforeDataFixersOptimize", "afterDataFixersOptimize");
        surround(main, crashReport, "beforeCrashReportPreload", "afterCrashReportPreload");
        main.instructions.insertBefore(runAndTick, hook("beforeRunAndTick"));
        main.instructions.insert(runAndTick, hook("afterRunAndTick"));
        worker.instructions.insertBefore(workerFirst, hook("bootstrapWorkerEntry"));
        worker.instructions.insertBefore(workerReturns.get(0), hook("bootstrapWorkerReturn"));
        return input;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && (desc == null || desc.equals(call.desc))) {
                if (found != null) return null;
                found = call;
            }
        }
        return found;
    }

    private static void surround(MethodNode method, MethodInsnNode call, String before, String after) {
        method.instructions.insertBefore(call, hook(before));
        method.instructions.insert(call, hook(after));
    }

    private static boolean containsBootstrapCall(MethodNode method) {
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && BOOTSTRAP.equals(call.owner)
                    && "bootStrap".equals(call.name)
                    && "()V".equals(call.desc)) {
                matches++;
            }
        }
        return matches == 1;
    }

    private static MethodInsnNode hook(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false);
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) return instruction;
        }
        return null;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.minecraft.client.main.Main"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_agent98_main_prefix_profile" };
    }
}
