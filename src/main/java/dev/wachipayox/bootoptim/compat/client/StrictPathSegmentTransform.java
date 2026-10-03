package dev.wachipayox.bootoptim.compat.client;

import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replace one exact pure operation, keeping head/tail injections, wrappers and branches intact. */
public final class StrictPathSegmentTransform {
    private StrictPathSegmentTransform() {}
    public static boolean rewrite(ClassNode owner) {
        if (!owner.name.equals("net/minecraft/FileUtil")) return false;
        var sites = new ArrayList<AbstractInsnNode>();
        MethodNode target = null;
        for (MethodNode method : owner.methods) {
            // MixinExtras may have moved the body into a renamed original method.
            // Exact field/input/instruction shape, not generated name, identifies the pure site.
            if (!method.desc.equals("(Ljava/lang/String;)Z")
                    || (method.access & Opcodes.ACC_STATIC) == 0) continue;
            for (AbstractInsnNode n : method.instructions) {
                if (n instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(owner.name) && field.name.equals("STRICT_PATH_SEGMENT_CHECK")
                        && field.desc.equals("Ljava/util/regex/Pattern;")
                        && n.getNext() instanceof VarInsnNode arg && arg.getOpcode() == Opcodes.ALOAD && arg.var == 0
                        && arg.getNext() instanceof MethodInsnNode matcher && matcher.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && matcher.owner.equals("java/util/regex/Pattern") && matcher.name.equals("matcher")
                        && matcher.desc.equals("(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;")
                        && matcher.getNext() instanceof MethodInsnNode matches && matches.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && matches.owner.equals("java/util/regex/Matcher") && matches.name.equals("matches")
                        && matches.desc.equals("()Z")) { sites.add(matcher); target = method; }
            }
        }
        // Labels/branches/additional calls inserted INSIDE the four-node sequence fail open.
        if (target == null || sites.size() != 1) return false;
        AbstractInsnNode matcher = sites.getFirst(), matches = matcher.getNext();
        target.instructions.set(matcher, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/wachipayox/bootoptim/optimization/client/StrictPathSegmentOperation", "evaluate",
                "(Ljava/util/regex/Pattern;Ljava/lang/String;)Z", false));
        target.instructions.remove(matches);
        return true;
    }
}
