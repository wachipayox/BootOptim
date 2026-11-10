package dev.wachipayox.bootoptim.zipfsagent;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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

    /** Keys already used in this JVM: a repeated key would define the same class name twice (LinkageError). */
    private static final Set<String> USED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** Default {@code Object.toString()} ends with {@code @<identity hash>}: not stable across launches. */
    private static final Pattern IDENTITY_HASH = Pattern.compile(".*@[0-9a-fA-F]{5,}$");

    /**
     * Unique per (inner class, target class) pair, which is what Mixin asks to be unique, and stable across launches.
     * Falls back to {@code UUID.randomUUID()} (the original behaviour) when the key was already used in this JVM or the
     * target has no stable text, logging {@code [WBOMBO-COMPAT] FALLBACK} once per reason.
     */
    public static UUID anonymousUuid(String originalName, Object target) {
        try {
            String text = String.valueOf(target);
            if (IDENTITY_HASH.matcher(text).matches()) {
                reportOnce("unstable_target", "target=" + target.getClass().getName());
                return UUID.randomUUID();
            }
            String key = originalName + "|" + text;
            if (!USED.add(key)) {
                reportOnce("duplicate_key", "key=" + key);
                return UUID.randomUUID();
            }
            return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            return UUID.randomUUID(); // the original behaviour; a name must never fail Mixin
        }
    }

    private static void reportOnce(String reason, String detail) {
        try {
            if (REPORTED.add(reason)) {
                ZipfsReadOnlyAgent.status("[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism="
                        + MixinDeterminismTransformer.MECHANISM + " mod=mixin reason=" + reason + " " + detail);
            }
        } catch (Throwable ignored) {
            // logging must not change the outcome
        }
    }

    /** Test hook. */
    static void resetForTest() {
        USED.clear();
        REPORTED.clear();
    }
}
