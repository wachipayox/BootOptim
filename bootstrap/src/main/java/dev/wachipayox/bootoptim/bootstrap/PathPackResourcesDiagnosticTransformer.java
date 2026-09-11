package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
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

/** Diagnostic-only early transformer. It never changes resource lookup/listing results. */
public final class PathPackResourcesDiagnosticTransformer implements ITransformer<ClassNode> {
    private static final String TARGET_CLASS = "net.minecraft.server.packs.PathPackResources";
    private static final String GET_RESOURCE_DESC = "(Lnet/minecraft/resources/ResourceLocation;Ljava/nio/file/Path;)Lnet/minecraft/server/packs/resources/IoSupplier;";
    private static final String LIST_RESOURCES_DESC = "(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode getResource = find(input, "getResource", GET_RESOURCE_DESC);
        MethodNode listResources = find(input, "listResources", LIST_RESOURCES_DESC);

        if (getResource != null) {
            injectEmptyResourceLocation(getResource);
        }
        if (listResources != null) {
            injectEmptyListPath(listResources);
        }

        System.out.println("[BootOptim PathPack early diagnostic] transform_applied getResource="
                + (getResource != null) + " listResources=" + (listResources != null));
        if (getResource == null || listResources == null) {
            System.out.println("[BootOptim PathPack early diagnostic] methods="
                    + input.methods.stream().map(candidate -> candidate.name + candidate.desc).toList());
        }
        return input;
    }

    private static MethodNode find(ClassNode input, String name, String desc) {
        return input.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && desc.equals(candidate.desc))
                .findFirst()
                .orElse(null);
    }

    private static void injectEmptyResourceLocation(MethodNode method) {
        LabelNode skip = new LabelNode();
        InsnList injected = new InsnList();
        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/resources/ResourceLocation", "getPath", "()Ljava/lang/String;", false));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false));
        injected.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        printLabelAndObject(injected, "[BootOptim PathPack early diagnostic] resource-location=", 0);
        printLabelAndObject(injected, "[BootOptim PathPack early diagnostic] resource-basePath=", 1);
        injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "dumpStack", "()V", false));
        injected.add(skip);
        method.instructions.insert(injected);
    }

    private static void injectEmptyListPath(MethodNode method) {
        LabelNode skip = new LabelNode();
        InsnList injected = new InsnList();
        injected.add(new VarInsnNode(Opcodes.ALOAD, 3));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false));
        injected.add(new JumpInsnNode(Opcodes.IFEQ, skip));

        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new LdcInsnNode("[BootOptim PathPack early diagnostic] list-packId="));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false));
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/packs/PathPackResources", "packId", "()Ljava/lang/String;", false));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new LdcInsnNode("[BootOptim PathPack early diagnostic] list-root="));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false));
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injected.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/server/packs/PathPackResources", "root", "Ljava/nio/file/Path;"));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false));

        printLabelAndObject(injected, "[BootOptim PathPack early diagnostic] list-packType=", 1);
        printLabelAndObject(injected, "[BootOptim PathPack early diagnostic] list-namespace=", 2);
        injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "dumpStack", "()V", false));
        injected.add(skip);
        method.instructions.insert(injected);
    }

    private static void printLabelAndObject(InsnList injected, String label, int local) {
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new LdcInsnNode(label));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false));
        injected.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injected.add(new VarInsnNode(Opcodes.ALOAD, local));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false));
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetPreClass(TARGET_CLASS));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.PRE_CLASS;
    }
}
