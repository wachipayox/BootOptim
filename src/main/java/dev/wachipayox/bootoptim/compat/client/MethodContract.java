package dev.wachipayox.bootoptim.compat.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

/** Code-only contract: ignores class/JAR versions, unrelated methods and debug data. */
public final class MethodContract {
    private MethodContract() { }
    public static String fingerprint(MethodNode m) throws Exception {
        Map<LabelNode,Integer> labels=new IdentityHashMap<>(); int at=0;
        for(AbstractInsnNode i:m.instructions){if(i instanceof LabelNode l)labels.put(l,at);if(i.getOpcode()>=0)at++;}
        StringBuilder s=new StringBuilder(m.name+m.desc+":"+m.access);
        for(AbstractInsnNode i:m.instructions){if(i.getOpcode()<0)continue;s.append(';').append(i.getOpcode()).append(':');
            if(i instanceof VarInsnNode n)s.append(n.var);
            else if(i instanceof FieldInsnNode n)s.append(n.owner).append('|').append(n.name).append('|').append(n.desc);
            else if(i instanceof MethodInsnNode n)s.append(n.owner).append('|').append(n.name).append('|').append(n.desc).append('|').append(n.itf);
            else if(i instanceof TypeInsnNode n)s.append(n.desc);
            else if(i instanceof IntInsnNode n)s.append(n.operand);
            else if(i instanceof IincInsnNode n)s.append(n.var).append('|').append(n.incr);
            else if(i instanceof LdcInsnNode n)s.append(n.cst instanceof Type?"asm.Type":n.cst instanceof Handle?"asm.Handle":n.cst.getClass().getName()).append('|').append(n.cst);
            else if(i instanceof JumpInsnNode n)s.append(labels.get(n.label));
            else if(i instanceof InvokeDynamicInsnNode n)s.append(n.name).append('|').append(n.desc).append('|').append(n.bsm).append('|').append(Arrays.deepToString(n.bsmArgs));
            else if(i instanceof TableSwitchInsnNode n)s.append(n.min).append('|').append(n.max).append('|').append(labels.get(n.dflt)).append('|').append(n.labels.stream().map(labels::get).toList());
            else if(i instanceof LookupSwitchInsnNode n)s.append(n.keys).append('|').append(labels.get(n.dflt)).append('|').append(n.labels.stream().map(labels::get).toList());
            else if(i instanceof MultiANewArrayInsnNode n)s.append(n.desc).append('|').append(n.dims);
            else if(!(i instanceof InsnNode))throw new IllegalArgumentException("Unknown instruction");
        }
        for(TryCatchBlockNode t:m.tryCatchBlocks)s.append(";catch:").append(labels.get(t.start)).append('|').append(labels.get(t.end)).append('|').append(labels.get(t.handler)).append('|').append(t.type);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
