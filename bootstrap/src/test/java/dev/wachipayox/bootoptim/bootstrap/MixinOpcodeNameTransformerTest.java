package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Printer;
import org.spongepowered.asm.util.Bytecode;

class MixinOpcodeNameTransformerTest {
    @Test
    void directLookupMatchesMixin087ForExhaustiveRelevantDomain() throws Exception {
        Set<Integer> values = new LinkedHashSet<>();
        for (int opcode = -4096; opcode <= 4096; opcode++) values.add(opcode);
        values.add(Integer.MIN_VALUE);
        values.add(Integer.MAX_VALUE);
        for (Field field : Opcodes.class.getDeclaredFields()) {
            if (field.getType() == Integer.TYPE) values.add(field.getInt(null));
        }

        List<String> mismatches = new ArrayList<>();
        for (int opcode : values) {
            String stock = Bytecode.getOpcodeName(opcode);
            String direct = MixinOpcodeNameTransformer.directOpcodeName(opcode);
            if (!stock.equals(direct)) mismatches.add(opcode + ":stock=" + stock + ",direct=" + direct);
        }
        if (!mismatches.isEmpty()) System.out.println("BOOTOPTIM_OPCODE_EQUIVALENCE_MISMATCHES " + mismatches);
        assertTrue(mismatches.isEmpty(), "opcode mismatches=" + mismatches);
    }

    @Test
    void printerTableHasNoNullHoleInDirectLookupRange() {
        assertTrue(Printer.OPCODES.length > Opcodes.IFNONNULL);
        for (int opcode = 1; opcode < Printer.OPCODES.length; opcode++) {
            assertNotNull(Printer.OPCODES[opcode], "opcode=" + opcode);
        }
    }

    @Test
    void exactRuntimeMixinClassMatchesGuard() throws Exception {
        try (InputStream stream = Bytecode.class.getResourceAsStream("Bytecode.class")) {
            assertNotNull(stream);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            MethodNode method = node.methods.stream()
                    .filter(candidate -> "getOpcodeName".equals(candidate.name)
                            && "(I)Ljava/lang/String;".equals(candidate.desc))
                    .findFirst().orElseThrow();
            List<String> shape = new ArrayList<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() < 0) continue;
                String detail = Printer.OPCODES[insn.getOpcode()];
                if (insn instanceof VarInsnNode var) detail += " var=" + var.var;
                if (insn instanceof LdcInsnNode ldc) detail += " cst=" + ldc.cst;
                if (insn instanceof MethodInsnNode call) detail += " call=" + call.owner + "." + call.name + call.desc;
                shape.add(detail);
            }
            if (!MixinOpcodeNameTransformer.matchesMixin087Wrapper(method)) {
                System.out.println("BOOTOPTIM_OPCODE_WRAPPER_SHAPE " + shape);
            }
            assertTrue(MixinOpcodeNameTransformer.matchesMixin087Wrapper(method), "wrapper shape=" + shape);

            method.instructions.remove(method.instructions.getFirst());
            assertFalse(MixinOpcodeNameTransformer.matchesMixin087Wrapper(method));
        }
    }
}
