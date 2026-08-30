package dev.wachipayox.bootoptim.bootstrap;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

/** Encodes nullable class parents in BootOptim's stable scan-cache wire format. */
final class ScanCacheTypeCodec {
    private ScanCacheTypeCodec() {
    }

    static String encodeNullable(@Nullable Type type) {
        return type == null ? "" : type.getInternalName();
    }

    static @Nullable Type decodeNullable(String internalName) {
        return internalName.isEmpty() ? null : Type.getObjectType(internalName);
    }
}
