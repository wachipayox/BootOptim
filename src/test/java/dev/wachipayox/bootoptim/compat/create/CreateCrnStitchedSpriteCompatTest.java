package dev.wachipayox.bootoptim.compat.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreateCrnStitchedSpriteCompatTest {
    @Test
    void patchesOnlyLegacyCacheWithCreateAndCrn() {
        assertTrue(CreateCrnStitchedSpriteCompat.shouldPatch(true, true, false));
    }

    @Test
    void leavesCurrentPonderConcurrentCacheUntouched() {
        assertFalse(CreateCrnStitchedSpriteCompat.shouldPatch(true, true, true));
    }

    @Test
    void leavesCreateWithoutCrnUntouched() {
        assertFalse(CreateCrnStitchedSpriteCompat.shouldPatch(true, false, false));
    }

    @Test
    void leavesUnrelatedPackUntouched() {
        assertFalse(CreateCrnStitchedSpriteCompat.shouldPatch(false, false, false));
    }
}
