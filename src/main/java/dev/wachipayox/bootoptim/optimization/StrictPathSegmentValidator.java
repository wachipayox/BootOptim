package dev.wachipayox.bootoptim.optimization;

import java.util.regex.Pattern;

/** Equivalent to the exact Minecraft 1.21.1 ASCII segment grammar; no input/result cache. */
public final class StrictPathSegmentValidator {
    private StrictPathSegmentValidator() {}

    public static boolean compatible(Pattern pattern) {
        return pattern != null && pattern.flags() == 0 && pattern.pattern().equals("[-._a-z0-9]+");
    }

    public static boolean matches(String segment) {
        int length = segment.length();
        if (length == 0) return false;
        for (int i = 0; i < length; i++) {
            char c = segment.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_')) return false;
        }
        return true;
    }

    /** The measured candidate includes the actual shape guard and null fallback. */
    public static boolean guarded(Pattern pattern, String segment) {
        return segment != null && compatible(pattern) ? matches(segment) : pattern.matcher(segment).matches();
    }

}
