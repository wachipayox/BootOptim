package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;
import org.spongepowered.asm.util.Bytecode;

class MixinOpcodeNameTransformerTest {
    @Test
    void directLookupMatchesMixin087ForExhaustiveRelevantDomain() throws Exception {
        // Every value which the stock reflective scan could possibly match, plus the complete
        // practical opcode/invalid neighbourhood and integer extremes.
        Set<Integer> values = new LinkedHashSet<>();
        for (int opcode = -4096; opcode <= 4096; opcode++) {
            values.add(opcode);
        }
        values.add(Integer.MIN_VALUE);
        values.add(Integer.MAX_VALUE);
        for (Field field : Opcodes.class.getDeclaredFields()) {
            if (field.getType() == Integer.TYPE) {
                values.add(field.getInt(null));
            }
        }

        for (int opcode : values) {
            assertEquals(
                    Bytecode.getOpcodeName(opcode),
                    MixinOpcodeNameTransformer.directOpcodeName(opcode),
                    "opcode=" + opcode);
        }
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
                    .findFirst()
                    .orElseThrow();
            assertTrue(MixinOpcodeNameTransformer.matchesMixin087Wrapper(method));

            // Any meaningful change to the wrapper must fail open rather than patch an unknown version.
            method.instructions.remove(method.instructions.getFirst());
            assertFalse(MixinOpcodeNameTransformer.matchesMixin087Wrapper(method));
        }
    }
}
