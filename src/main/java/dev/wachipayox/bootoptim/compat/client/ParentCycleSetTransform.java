package dev.wachipayox.bootoptim.compat.client;

import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Final instruction-site transform: unknown/escaping locals are never rewritten. */
public final class ParentCycleSetTransform implements Opcodes {
    public static final String OWNER = "net/minecraft/client/renderer/block/model/BlockModel";
    private ParentCycleSetTransform() {}

    public static boolean rewrite(ClassNode owner) {
        if (!OWNER.equals(owner.name)) return false;
        MethodNode target = null;
        MethodInsnNode factory = null;
        VarInsnNode store = null;
        int factories = 0;
        for (MethodNode method : owner.methods) {
            if (!method.desc.equals("(Ljava/util/function/Function;)V") || (method.access & ACC_STATIC) != 0) continue;
            for (AbstractInsnNode n : method.instructions) {
                if (n instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                        && call.owner.equals("com/google/common/collect/Sets") && call.name.equals("newLinkedHashSet")
                        && call.desc.equals("()Ljava/util/LinkedHashSet;")) {
                    factories++;
                    if (!(call.getNext() instanceof VarInsnNode v) || v.getOpcode() != ASTORE || v.var < 2) return false;
                    target = method; factory = call; store = v;
                }
            }
        }
        if (factories != 1) return false;
        var reads = new ArrayList<VarInsnNode>();
        var entries = new java.util.HashSet<LabelNode>();
        for (AbstractInsnNode n : target.instructions) {
            if (n instanceof JumpInsnNode j) entries.add(j.label);
            if (n instanceof TableSwitchInsnNode s) { entries.add(s.dflt); entries.addAll(s.labels); }
            if (n instanceof LookupSwitchInsnNode s) { entries.add(s.dflt); entries.addAll(s.labels); }
        }
        for (TryCatchBlockNode handler : target.tryCatchBlocks) {
            entries.add(handler.handler); entries.add(handler.start); entries.add(handler.end);
        }
        int adds = 0, contains = 0, streams = 0;
        boolean initialized = false;
        for (AbstractInsnNode n : target.instructions) {
            if (n == store) { initialized = true; continue; }
            if (n instanceof IincInsnNode i && i.var == store.var) return false;
            if (!(n instanceof VarInsnNode v) || v.var != store.var) continue;
            if (!initialized || v.getOpcode() != ALOAD) return false;
            AbstractInsnNode next = v.getNext();
            // javac can split the logging line between receiver load and stream().
            while (next instanceof LineNumberNode || next instanceof LabelNode label && !entries.contains(label)) next = next.getNext();
            if (next instanceof VarInsnNode arg && arg.getOpcode() == ALOAD && arg.var != store.var) next = arg.getNext();
            if (!(next instanceof MethodInsnNode call) || call.getOpcode() != INVOKEINTERFACE
                    || !call.owner.equals("java/util/Set")) return false;
            if (call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z")) adds++;
            else if (call.name.equals("contains") && call.desc.equals("(Ljava/lang/Object;)Z")) contains++;
            else if (call.name.equals("stream") && call.desc.equals("()Ljava/util/stream/Stream;")
                    && (v.getNext() == call || v.getNext().getOpcode() < 0)) streams++;
            else return false;
            reads.add(v);
        }
        // Require the stock local-use census, no partially recognized custom consumers.
        if (adds != 1 || contains != 1 || streams != 1) return false;
        target.instructions.set(factory, new InsnNode(ACONST_NULL));
        for (VarInsnNode read : reads) {
            InsnList ensure = new InsnList();
            ensure.add(new MethodInsnNode(INVOKESTATIC,
                    "dev/wachipayox/bootoptim/optimization/ParentCycleSet", "ensure",
                    "(Ljava/util/Set;)Ljava/util/Set;", false));
            ensure.add(new InsnNode(DUP));
            ensure.add(new VarInsnNode(ASTORE, store.var));
            target.instructions.insert(read, ensure);
        }
        target.maxStack += 2;
        return true;
    }
}
