package dev.wachipayox.bootoptim.zipfsagent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic replacements for the two {@code UUID.randomUUID()} calls Mixin uses to name generated code
 * ({@code md<6 hex>$} prefix of merged synthetic methods, {@code $Anonymous$<uuid>} inner classes). Random names make
 * the bytes of every Mixin-transformed class differ between launches, so a JDK 25 AOT cache can never serve them.
 * Loaded on the boot class path by {@link MixinDeterminismTransformer}; never throws.
 */
public final class MixinNames {

    /** Same value on every launch. Only one Mixin processor merges into a given class, so the prefix cannot collide. */
    private static final UUID SESSION = UUID.nameUUIDFromBytes("bootoptim-mixin-session".getBytes(StandardCharsets.UTF_8));

    private MixinNames() {
    }

    public static UUID sessionUuid() {
        return SESSION;
    }

    /** Unique per (inner class, target class) pair, which is what Mixin asks to be unique, and stable across launches. */
    public static UUID anonymousUuid(String originalName, Object target) {
        try {
            String key = originalName + "|" + target;
            return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            return UUID.randomUUID(); // the original behaviour; a name must never fail Mixin
        }
    }
}
