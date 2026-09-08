package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Injects the minimum causal post-discovery trace hooks into FML's ModLoader. */
public final class FmlLoadingTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/neoforged/fml/ModLoader";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/FmlLoadingTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        for (var method : input.methods) {
            var original = method.instructions.toArray();
            boolean ownsBackgroundScanBarrier = false;
            for (var instruction : original) {
                if (instruction instanceof MethodInsnNode invoke
                        && "waitForScanToComplete".equals(invoke.name)) {
                    ownsBackgroundScanBarrier = true;
                    break;
                }
            }

            if (ownsBackgroundScanBarrier) {
                method.instructions.insert(call("beginGatherAndInitialize", "()V"));
                for (var instruction : original) {
                    if (instruction instanceof MethodInsnNode invoke
                            && "waitForScanToComplete".equals(invoke.name)) {
                        method.instructions.insertBefore(invoke, call("beforeBackgroundScanWait", "()V"));
                        method.instructions.insert(invoke, call("afterBackgroundScanWait", "()V"));
                    }
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, call("endGatherAndInitialize", "()V"));
                    }
                }
            }

            for (var instruction : original) {
                if (!(instruction instanceof MethodInsnNode invoke)) continue;
                if (!"constructMod".equals(invoke.name) || !"()V".equals(invoke.desc)) continue;

                // The exact FML 4.0.x bytecode may statically own this call on a concrete container subtype.
                // Matching the zero-arg constructMod call inside ModLoader is structural and avoids synthetic
                // lambda numbering and static-owner drift while remaining scoped to this one target class.
                var before = new InsnList();
                before.add(new InsnNode(Opcodes.DUP));
                before.add(call("beginModConstruction", "(Lnet/neoforged/fml/ModContainer;)V"));
                method.instructions.insertBefore(invoke, before);
                method.instructions.insert(invoke, call("endModConstruction", "()V"));
            }
        }
        return input;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.neoforged.fml.ModLoader"));
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
