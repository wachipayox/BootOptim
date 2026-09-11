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
 * Diagnostic-only, fail-closed matcher for NeoForge 1.21.1's patched client Main handoff:
 * BackgroundWaiter.runAndTick(() -> Bootstrap.bootStrap(), ImmediateWindowHandler::renderTick).
 *
 * <p>No executor is wrapped and no task order is changed. We only mark the exact callsite and
 * the exact synthetic lambda which invokes Bootstrap.bootStrap().</p>
 */
public final class MinecraftMainLifecycleTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/client/main/Main";
    private static final String BACKGROUND_WAITER = "net/neoforged/fml/loading/BackgroundWaiter";
    private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
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

        List<MethodInsnNode> runAndTickCalls = new ArrayList<>();
        for (AbstractInsnNode instruction : main.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && BACKGROUND_WAITER.equals(call.owner)
                    && "runAndTick".equals(call.name)) {
                runAndTickCalls.add(call);
            }
        }
        if (runAndTickCalls.size() != 1) return input;

        AbstractInsnNode workerFirst = firstExecutable(worker);
        if (workerFirst == null) return input;
        List<AbstractInsnNode> workerReturns = new ArrayList<>();
        for (AbstractInsnNode instruction : worker.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.IRETURN
                    || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN) {
                workerReturns.add(instruction);
            }
        }
        if (workerReturns.size() != 1) return input;

        MethodInsnNode runAndTick = runAndTickCalls.get(0);
        main.instructions.insertBefore(runAndTick, hook("beforeRunAndTick"));
        main.instructions.insert(runAndTick, hook("afterRunAndTick"));
        worker.instructions.insertBefore(workerFirst, hook("bootstrapWorkerEntry"));
        worker.instructions.insertBefore(workerReturns.get(0), hook("bootstrapWorkerReturn"));
        return input;
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
        return new String[] { "boot_optim_agent94_main_lifecycle" };
    }
}
