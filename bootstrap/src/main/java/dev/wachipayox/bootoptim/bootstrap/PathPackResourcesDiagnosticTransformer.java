package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Diagnostic-only early transformer for the exact 1.21.1 PathPackResources empty-path error.
 *
 * <p>The injected code only prints the static helper's arguments and current producer stack when
 * the ResourceLocation path is empty. It does not alter arguments, return values, exceptions,
 * pack/resource state, or control flow beyond the diagnostic side effect.</p>
 */
public final class PathPackResourcesDiagnosticTransformer implements ITransformer<ClassNode> {
    private static final String TARGET_CLASS = "net.minecraft.server.packs.PathPackResources";
    private static final String TARGET_METHOD = "getResource";
    private static final String TARGET_DESC = "(Lnet/minecraft/resources/ResourceLocation;Ljava/nio/file/Path;)Lnet/minecraft/server/packs/resources/IoSupplier;";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode method = input.methods.stream()
                .filter(candidate -> TARGET_METHOD.equals(candidate.name) && TARGET_DESC.equals(candidate.desc))
                .findFirst()
                .orElse(null);
        if (method == null) {
            System.out.println("[BootOptim PathPack early diagnostic] transform_miss methods="
                    + input.methods.stream().map(candidate -> candidate.name + candidate.desc).toList());
            return input;
        }

        LabelNode skip = new LabelNode();
        InsnList injected = new InsnList();
        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injected.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/resources/ResourceLocation",
                "getPath",
                "()Ljava/lang/String;",
                false));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false));
        injected.add(new JumpInsnNode(Opcodes.IFEQ, skip));

        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new LdcInsnNode("[BootOptim PathPack early diagnostic] location="));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false));
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false));

        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new LdcInsnNode("[BootOptim PathPack early diagnostic] basePath="));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false));
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false));
        injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "dumpStack", "()V", false));
        injected.add(skip);

        method.instructions.insert(injected);
        System.out.println("[BootOptim PathPack early diagnostic] transform_applied descriptor=" + TARGET_DESC);
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetPreClass(TARGET_CLASS));
    }
}
