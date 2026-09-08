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
 * Instruments only Minecraft's exact {@code Bootstrap.bootStrap()V} body.
 *
 * <p>The earlier Main.main callsite candidate is deliberately not used: hosted exact-pack showed no hook from that
 * target on NeoForge 21.1.248. This narrower game-class method has an independent semantic boundary and fails closed
 * unless exactly one matching method with at least one normal RETURN exists.</p>
 */
public final class MinecraftBootstrapTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/server/Bootstrap";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftBootstrapTraceHooks";

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

        bootstrap.instructions.insertBefore(firstExecutable, call("beginBootstrap"));
        for (var normalReturn : normalReturns) {
            bootstrap.instructions.insertBefore(normalReturn, call("endBootstrap"));
        }
        return input;
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) return instruction;
        }
        return null;
    }

    private static MethodInsnNode call(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false);
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
        return new String[] { "boot_optim_minecraft_bootstrap_trace" };
    }
}
