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
 * Diagnostic-only copy of the #207 strict matcher. It records acceptance immediately before mutation and
 * injects one entry marker into the exact net.minecraft.server.Bootstrap.bootStrap()V method.
 */
public final class MinecraftBootstrapForkProfileTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/server/Bootstrap";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftBootstrapForkProfileHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodNode bootstrap = null;
        for (var method : input.methods) {
            if ("bootStrap".equals(method.name) && "()V".equals(method.desc)) {
                if (bootstrap != null) return input;
                bootstrap = method;
            }
        }
        if (bootstrap == null) return input;

        AbstractInsnNode firstExecutable = firstExecutable(bootstrap);
        if (firstExecutable == null) return input;

        List<AbstractInsnNode> normalReturns = new ArrayList<>();
        for (var instruction : bootstrap.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) normalReturns.add(instruction);
        }
        if (normalReturns.isEmpty()) return input;

        MinecraftBootstrapForkProfileHooks.transformAccepted();
        bootstrap.instructions.insertBefore(firstExecutable, new MethodInsnNode(
                Opcodes.INVOKESTATIC, HOOKS, "entry", "()V", false));
        return input;
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
        return Set.of(Target.targetClass("net.minecraft.server.Bootstrap"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_agent94_bootstrap_profile" };
    }
}
