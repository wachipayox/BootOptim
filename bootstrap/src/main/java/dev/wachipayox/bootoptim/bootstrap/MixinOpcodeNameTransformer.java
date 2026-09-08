package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.Printer;

/**
 * Experimental, opt-in replacement for the reflective Mixin 0.8.7 opcode-name lookup.
 *
 * <p>The target method is patched only when its bytecode still has the exact 0.8.7 wrapper shape.
 * A mismatch returns the original class unchanged. The replacement reads ASM's existing immutable
 * {@link Printer#OPCODES} table, so it does not introduce a BootOptim cache or cross-loader state.</p>
 */
final class MixinOpcodeNameTransformer implements ITransformer<ClassNode> {
    static final String ENABLE_PROPERTY = "boot_optim.mixinOpcodeNameDirect";
    static final String TARGET_CLASS = "org.spongepowered.asm.util.Bytecode";
    private static final String TARGET_METHOD = "getOpcodeName";
    private static final String TARGET_DESC = "(I)Ljava/lang/String;";
    private static final String STOCK_HELPER_DESC = "(ILjava/lang/String;I)Ljava/lang/String;";

    private boolean reported;

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode target = null;
        for (MethodNode method : input.methods) {
            if (TARGET_METHOD.equals(method.name) && TARGET_DESC.equals(method.desc)) {
                target = method;
                break;
            }
        }

        if (target == null || !matchesMixin087Wrapper(target)) {
            report(false, "mixin_0_8_7_method_guard_mismatch");
            return input;
        }

        replaceBody(target);
        report(true, "mixin_0_8_7_wrapper_matched_printer_opcode_table");
        return input;
    }

    private void report(boolean enabled, String reason) {
        if (!reported) {
            reported = true;
            StartupDiagnostics.optimization("mixin_opcode_name_direct", enabled, reason);
            System.out.printf("BOOTOPTIM_MIXIN_OPCODE_NAME_DIRECT applied=%s reason=%s%n", enabled, reason);
        }
    }

    static boolean matchesMixin087Wrapper(MethodNode method) {
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                real.add(insn);
            }
        }
        if (real.size() != 5
                || real.get(0).getOpcode() != Opcodes.ILOAD
                || real.get(1).getOpcode() != Opcodes.LDC
                || real.get(2).getOpcode() != Opcodes.ICONST_1
                || real.get(3).getOpcode() != Opcodes.INVOKESTATIC
                || real.get(4).getOpcode() != Opcodes.ARETURN) {
            return false;
        }
        if (!(real.get(0) instanceof VarInsnNode load) || load.var != 0) {
            return false;
        }
        if (!(real.get(1) instanceof LdcInsnNode ldc) || !"UNINITIALIZED_THIS".equals(ldc.cst)) {
            return false;
        }
        if (!(real.get(3) instanceof MethodInsnNode call)) {
            return false;
        }
        return TARGET_CLASS.replace('.', '/').equals(call.owner)
                && TARGET_METHOD.equals(call.name)
                && STOCK_HELPER_DESC.equals(call.desc)
                && !call.itf;
    }

    static String directOpcodeName(int opcode) {
        if (opcode < 0) {
            return "UNKNOWN";
        }
        if (opcode > 0 && opcode < Printer.OPCODES.length) {
            String name = Printer.OPCODES[opcode];
            if (name != null) {
                return name;
            }
        }
        return String.valueOf(opcode);
    }

    private static void replaceBody(MethodNode method) {
        InsnList instructions = new InsnList();
        LabelNode decimal = new LabelNode();
        LabelNode unknown = new LabelNode();

        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new JumpInsnNode(Opcodes.IFLT, unknown));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new JumpInsnNode(Opcodes.IFEQ, decimal));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "org/objectweb/asm/util/Printer",
                "OPCODES",
                "[Ljava/lang/String;"));
        instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, decimal));
        instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "org/objectweb/asm/util/Printer",
                "OPCODES",
                "[Ljava/lang/String;"));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new InsnNode(Opcodes.AALOAD));
        instructions.add(new InsnNode(Opcodes.ARETURN));

        instructions.add(decimal);
        instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/String",
                "valueOf",
                "(I)Ljava/lang/String;",
                false));
        instructions.add(new InsnNode(Opcodes.ARETURN));

        instructions.add(unknown);
        instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        instructions.add(new LdcInsnNode("UNKNOWN"));
        instructions.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.clear();
        method.instructions.add(instructions);
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxLocals = 1;
        method.maxStack = 2;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass(TARGET_CLASS));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }
}
