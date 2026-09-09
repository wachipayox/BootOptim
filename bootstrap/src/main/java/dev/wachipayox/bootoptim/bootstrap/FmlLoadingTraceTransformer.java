package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.ArrayList;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Instruments the game-layer NeoForge boundary that calls into FML mod construction.
 *
 * <p>FML's own {@code ModLoader} lives in the MC-BOOTSTRAP/SERVICE layer in 4.0.43 and is not a reliable target for
 * ordinary transformation-service transformers. {@code CommonModLoader} is a transformed NeoForge game-layer class
 * and has one direct, version-pinned call to {@code ModLoader.gatherAndInitializeMods}. This transformer wraps only
 * that call and its existing periodic callback; it does not target FML internals, executors or futures.</p>
 */
public final class FmlLoadingTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/neoforged/neoforge/internal/CommonModLoader";
    private static final String FML_MOD_LOADER = "net/neoforged/fml/ModLoader";
    private static final String GATHER_DESC =
            "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/FmlLoadingTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        var matches = new ArrayList<MethodInsnNode>();
        for (var method : input.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode invoke
                        && FML_MOD_LOADER.equals(invoke.owner)
                        && "gatherAndInitializeMods".equals(invoke.name)
                        && GATHER_DESC.equals(invoke.desc)) {
                    matches.add(invoke);
                }
            }
        }

        // Version-pinned fail-open contract: instrument only the known single callsite shape.
        if (matches.size() != 1) return input;
        var invoke = matches.getFirst();
        var method = input.methods.stream()
                .filter(candidate -> candidate.instructions.indexOf(invoke) >= 0)
                .findFirst()
                .orElse(null);
        if (method == null) return input;

        var before = new InsnList();
        before.add(call("beginGatherAndInitialize", "()V"));
        // At this point the invocation arguments are already on the operand stack; Runnable is the top value.
        before.add(call("wrapPeriodicTask", "(Ljava/lang/Runnable;)Ljava/lang/Runnable;"));
        method.instructions.insertBefore(invoke, before);
        method.instructions.insert(invoke, call("endGatherAndInitialize", "()V"));
        return input;
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(
                org.objectweb.asm.Opcodes.INVOKESTATIC,
                HOOKS,
                name,
                desc,
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
