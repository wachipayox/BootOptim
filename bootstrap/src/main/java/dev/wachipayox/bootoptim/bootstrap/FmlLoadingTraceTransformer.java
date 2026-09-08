package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Instruments the game-layer NeoForge boundary that calls into FML mod construction.
 *
 * <p>FML's own {@code ModLoader} lives in the MC-BOOTSTRAP/SERVICE layer in 4.0.43 and is not a reliable target for
 * ordinary transformation-service transformers. {@code CommonModLoader} is a transformed NeoForge game-layer class.
 * The existing gather wrapper remains unchanged; additionally, the exact {@code begin(Runnable, boolean)} prefix is
 * bracketed only when that method contains exactly one direct {@code ModLoader.gatherAndInitializeMods} call. This
 * gives a separately observable pre-gather prefix without transforming {@code ClientModLoader} or changing lifecycle.
 * </p>
 */
public final class FmlLoadingTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/neoforged/neoforge/internal/CommonModLoader";
    private static final String BEGIN_DESC = "(Ljava/lang/Runnable;Z)V";
    private static final String FML_MOD_LOADER = "net/neoforged/fml/ModLoader";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/FmlLoadingTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        instrumentExactBeginPrefix(input);

        // Preserve the already-validated #202 gather wrapper independently of the stricter prefix matcher.
        for (var method : input.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode invoke) || !isGatherCall(invoke)) continue;
                method.instructions.insertBefore(invoke, call("beginGatherAndInitialize"));
                method.instructions.insert(invoke, call("endGatherAndInitialize"));
            }
        }
        return input;
    }

    private static void instrumentExactBeginPrefix(ClassNode input) {
        MethodNode begin = null;
        for (var method : input.methods) {
            if (!"begin".equals(method.name) || !BEGIN_DESC.equals(method.desc)) continue;
            if (begin != null) return;
            begin = method;
        }
        if (begin == null) return;

        AbstractInsnNode firstExecutable = firstExecutable(begin);
        if (firstExecutable == null) return;

        MethodInsnNode gather = null;
        for (var instruction : begin.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invoke) || !isGatherCall(invoke)) continue;
            if (gather != null) return;
            gather = invoke;
        }
        if (gather == null) return;

        begin.instructions.insertBefore(firstExecutable, call("beginCommonModLoaderPrefix"));
        begin.instructions.insertBefore(gather, call("endCommonModLoaderPrefix"));
    }

    private static boolean isGatherCall(MethodInsnNode invoke) {
        return FML_MOD_LOADER.equals(invoke.owner)
                && "gatherAndInitializeMods".equals(invoke.name)
                && invoke.desc.endsWith(")V");
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) return instruction;
        }
        return null;
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
