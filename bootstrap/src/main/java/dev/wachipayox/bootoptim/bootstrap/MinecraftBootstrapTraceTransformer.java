package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Instruments only NeoForge's patched Minecraft client bootstrap boundary in game-layer Main.main.
 * The matcher is intentionally fail-closed: exactly one BackgroundWaiter.runAndTick and one later
 * ClientModLoader.begin() anchor must exist in main(String[]), otherwise the class is left untouched.
 */
public final class MinecraftBootstrapTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/client/main/Main";
    private static final String BACKGROUND_WAITER = "net/neoforged/fml/loading/BackgroundWaiter";
    private static final String CLIENT_MOD_LOADER = "net/neoforged/neoforge/client/loading/ClientModLoader";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftBootstrapTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        for (var method : input.methods) {
            if (!"main".equals(method.name) || !"([Ljava/lang/String;)V".equals(method.desc)) continue;

            MethodInsnNode start = null;
            MethodInsnNode end = null;
            boolean duplicateStart = false;
            boolean duplicateEnd = false;
            boolean sawStart = false;

            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode invoke)) continue;
                if (BACKGROUND_WAITER.equals(invoke.owner) && "runAndTick".equals(invoke.name)) {
                    if (start != null) duplicateStart = true;
                    else start = invoke;
                    sawStart = true;
                } else if (CLIENT_MOD_LOADER.equals(invoke.owner)
                        && "begin".equals(invoke.name)
                        && "()V".equals(invoke.desc)) {
                    if (end != null) duplicateEnd = true;
                    else if (sawStart) end = invoke;
                    else end = invoke;
                }
            }

            if (start == null || end == null || duplicateStart || duplicateEnd || !comesBefore(start, end)) return input;
            method.instructions.insertBefore(start, call("beginBootstrapAndValidate"));
            method.instructions.insertBefore(end, call("endBootstrapAndValidate"));
            return input;
        }
        return input;
    }

    private static boolean comesBefore(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode current = first; current != null; current = current.getNext()) {
            if (current == second) return true;
        }
        return false;
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
        return Set.of(Target.targetClass("net.minecraft.client.main.Main"));
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
