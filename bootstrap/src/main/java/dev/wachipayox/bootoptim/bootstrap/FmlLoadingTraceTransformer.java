package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Instruments the game-layer NeoForge boundary that calls into FML mod construction.
 *
 * <p>FML's own {@code ModLoader} lives in the MC-BOOTSTRAP/SERVICE layer in 4.0.43 and is not a reliable target for
 * ordinary transformation-service transformers. {@code CommonModLoader} is a transformed NeoForge game-layer class
 * and has one direct call to {@code ModLoader.gatherAndInitializeMods}; wrapping that call gives a causal boundary
 * without changing executors, futures, callbacks, classloading order or the FML implementation itself.</p>
 */
public final class FmlLoadingTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/neoforged/neoforge/internal/CommonModLoader";
    private static final String FML_MOD_LOADER = "net/neoforged/fml/ModLoader";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/FmlLoadingTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        for (var method : input.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode invoke)) continue;
                if (!FML_MOD_LOADER.equals(invoke.owner)
                        || !"gatherAndInitializeMods".equals(invoke.name)
                        || !invoke.desc.endsWith(")V")) continue;

                method.instructions.insertBefore(invoke, call("beginGatherAndInitialize"));
                method.instructions.insert(invoke, call("endGatherAndInitialize"));
            }
        }
        return input;
    }

    private static MethodInsnNode call(String name) {
        return new MethodInsnNode(
                org.objectweb.asm.Opcodes.INVOKESTATIC,
                HOOKS,
                name,
                "()V",
                false);
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.neoforged.neoforge.internal.CommonModLoader"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_fml_causal_trace" };
    }
}
