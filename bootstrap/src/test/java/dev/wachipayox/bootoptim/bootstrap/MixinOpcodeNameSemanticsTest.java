package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.spongepowered.asm.util.Bytecode;

/**
 * Locks down the observable name semantics of Mixin 0.8.7's reflective
 * Bytecode.getOpcodeName(int) before considering a direct-table replacement.
 *
 * <p>The obvious upstream-style Printer.OPCODES lookup is not quite equivalent:
 * stock 0.8.7 deliberately skips reflection for opcode 0 and therefore returns
 * the decimal string "0" instead of "NOP".</p>
 */
final class MixinOpcodeNameSemanticsTest {
    @Test
    void directCandidateMatchesStockAcrossOpcodeAndInvalidBoundaryDomain() {
        int upper = Printer.OPCODES.length + 1024;
        for (int opcode = -1024; opcode <= upper; opcode++) {
            assertEquals(Bytecode.getOpcodeName(opcode), directCandidate(opcode), "opcode=" + opcode);
        }

        assertEquals(Bytecode.getOpcodeName(Integer.MIN_VALUE), directCandidate(Integer.MIN_VALUE));
        assertEquals(Bytecode.getOpcodeName(Integer.MAX_VALUE), directCandidate(Integer.MAX_VALUE));
    }

    @Test
    void everyReflectivelyReachableIntegerValueIsCoveredByTheDirectCandidate() throws IllegalAccessException {
        boolean anchored = false;
        Set<Integer> values = new HashSet<>();

        for (Field field : Opcodes.class.getDeclaredFields()) {
            if (!anchored && !field.getName().equals("UNINITIALIZED_THIS")) {
                continue;
            }
            anchored = true;
            if (field.getType() == Integer.TYPE) {
                values.add(field.getInt(null));
            }
        }

        assertTrue(anchored, "ASM Opcodes no longer contains the Mixin 0.8.7 anchor field");
        for (int value : values) {
            assertEquals(Bytecode.getOpcodeName(value), directCandidate(value), "declared int value=" + value);
        }
    }

    @Test
    void invalidOpcodeContractIsExplicit() {
        assertEquals("UNKNOWN", Bytecode.getOpcodeName(-1));
        assertEquals("UNKNOWN", directCandidate(-1));
        assertEquals("0", Bytecode.getOpcodeName(0));
        assertEquals("0", directCandidate(0));

        int invalid = Printer.OPCODES.length + 1000;
        assertEquals(Integer.toString(invalid), Bytecode.getOpcodeName(invalid));
        assertEquals(Integer.toString(invalid), directCandidate(invalid));
    }

    /**
     * Candidate semantics for a future direct Mixin fork or supported launcher patch.
     * This helper intentionally lives in tests only: BootOptim cannot safely replace a
     * class already owned by Mixin in ModLauncher's SERVICE layer.
     */
    private static String directCandidate(int opcode) {
        if (opcode < 0) {
            return "UNKNOWN";
        }
        if (opcode == 0) {
            return "0";
        }
        if (opcode < Printer.OPCODES.length) {
            String name = Printer.OPCODES[opcode];
            if (name != null) {
                return name;
            }
        }
        return Integer.toString(opcode);
    }
}
