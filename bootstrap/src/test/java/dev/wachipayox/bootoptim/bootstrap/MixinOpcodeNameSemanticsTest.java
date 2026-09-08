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
 * <p>A blind Printer.OPCODES lookup is not equivalent. Stock 0.8.7 skips
 * reflection for opcode 0, and ASM's printer also names raw JVM opcodes for
 * which {@link Opcodes} deliberately exposes no primitive-int constant. The
 * legacy method returns decimal strings for both cases.</p>
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
    void invalidAndRawJvmOpcodeFallbackContractIsExplicit() {
        assertEquals("UNKNOWN", Bytecode.getOpcodeName(-1));
        assertEquals("UNKNOWN", directCandidate(-1));
        assertEquals("0", Bytecode.getOpcodeName(0));
        assertEquals("0", directCandidate(0));

        // Printer.OPCODES has names for these class-file opcodes, but Opcodes has
        // no public primitive-int constants for them, so Mixin 0.8.7 returns digits.
        int[] rawHoles = {19, 20, 26, 45, 59, 78, 196};
        for (int opcode : rawHoles) {
            assertEquals(Integer.toString(opcode), Bytecode.getOpcodeName(opcode), "raw hole=" + opcode);
            assertEquals(Integer.toString(opcode), directCandidate(opcode), "raw hole=" + opcode);
        }

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
        if (isLegacyReflectedOpcode(opcode)) {
            return Printer.OPCODES[opcode];
        }
        return Integer.toString(opcode);
    }

    /**
     * Positive opcode values for primitive-int fields declared by ASM Opcodes after
     * UNINITIALIZED_THIS in the resolved NeoForge/Mixin contract. NOP (0) is
     * deliberately excluded because Mixin 0.8.7's minimum lookup value is 1.
     */
    private static boolean isLegacyReflectedOpcode(int opcode) {
        return (opcode >= 1 && opcode <= 18)
                || (opcode >= 21 && opcode <= 25)
                || (opcode >= 46 && opcode <= 58)
                || (opcode >= 79 && opcode <= 195)
                || (opcode >= 197 && opcode <= 199);
    }
}
