package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Version-pinned marker at the NeoForge 1.21.1 GAME-layer callsite into ClientModLoader.begin.
 *
 * <p>The retired ClientModLoader matcher is not touched. This targets Minecraft and accepts only one exact invocation
 * with the official 1.21.1 descriptor, and only when that invocation occurs in a constructor. Drift or ambiguity
 * leaves the class untouched.</p>
 */
public final class MinecraftClientTransitionTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/client/Minecraft";
    private static final String CLIENT_MOD_LOADER = "net/neoforged/neoforge/client/loading/ClientModLoader";
    private static final String BEGIN_DESC = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftClientTransitionTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodInsnNode targetCall = null;
        org.objectweb.asm.tree.MethodNode ownerMethod = null;
        for (var method : input.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !isExactClientBegin(call)) continue;
                if (targetCall != null) return input;
                targetCall = call;
                ownerMethod = method;
            }
        }
        if (targetCall == null || ownerMethod == null || !"<init>".equals(ownerMethod.name)) return input;

        ownerMethod.instructions.insertBefore(targetCall, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HOOKS,
                "markClientModLoaderEntry",
                "()V",
                false));
        return input;
    }

    private static boolean isExactClientBegin(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && CLIENT_MOD_LOADER.equals(call.owner)
                && "begin".equals(call.name)
                && BEGIN_DESC.equals(call.desc);
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.minecraft.client.Minecraft"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_minecraft_client_transition_trace" };
    }
}
