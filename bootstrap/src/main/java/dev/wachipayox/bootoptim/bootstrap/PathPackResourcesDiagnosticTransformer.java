package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;

/**
 * Validation-only source-equivalent shim for the exact 1.21.1 CITResewn broken-path probe.
 *
 * <p>Agent 133 attribution proved every exact-pack call with an empty list path comes from
 * {@code citresewn$brokenpaths$parseMetadata}. Stock 1.21.1 rejects that empty path in
 * {@code FileUtil.decomposePath} before enumerating anything, invokes no ResourceOutput callback,
 * throws no exception to the caller, and simply returns after logging the error. Returning at the
 * same method boundary therefore preserves the resource/metadata result while avoiding the invalid
 * source call. This transformer exists only to validate the proposed upstream/fork source fix; it
 * is not a production BootOptim workaround.</p>
 */
public final class PathPackResourcesDiagnosticTransformer implements ITransformer<ClassNode> {
    private static final String TARGET_CLASS = "net.minecraft.server.packs.PathPackResources";
    private static final String LIST_RESOURCES_DESC = "(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode listResources = input.methods.stream()
                .filter(candidate -> "listResources".equals(candidate.name)
                        && LIST_RESOURCES_DESC.equals(candidate.desc))
                .findFirst()
                .orElse(null);
        if (listResources == null) {
            System.out.println("[BootOptim PathPack validation] transform_miss");
            return input;
        }

        LabelNode continueStock = new LabelNode();
        InsnList injected = new InsnList();
        injected.add(new VarInsnNode(Opcodes.ALOAD, 3));
        injected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false));
        injected.add(new JumpInsnNode(Opcodes.IFEQ, continueStock));
        injected.add(new InsnNode(Opcodes.RETURN));
        injected.add(continueStock);
        listResources.instructions.insert(injected);

        System.out.println("[BootOptim PathPack validation] source_equivalent_empty_list_noop_applied");
        return input;
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
