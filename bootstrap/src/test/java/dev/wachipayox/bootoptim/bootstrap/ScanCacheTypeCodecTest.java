package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class ScanCacheTypeCodecTest {
    @Test
    void roundTripsNormalParent() {
        Type parent = Type.getObjectType("java/lang/Object");
        assertEquals(parent, ScanCacheTypeCodec.decodeNullable(ScanCacheTypeCodec.encodeNullable(parent)));
    }

    @Test
    void roundTripsMissingParent() {
        assertEquals("", ScanCacheTypeCodec.encodeNullable(null));
        assertNull(ScanCacheTypeCodec.decodeNullable(""));
    }
}
