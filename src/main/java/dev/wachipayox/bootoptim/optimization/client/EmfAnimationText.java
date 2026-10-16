package dev.wachipayox.bootoptim.optimization.client;

import java.lang.reflect.Field;

/**
 * EMF {@code EMFJemData.processAnimAndKeyString(part, text)} runs three regex passes over every
 * animation key and expression (strip {@code \s}, then the {@code this} and {@code part} tokens).
 * {@link EmfExpressionParsing#animationText} does the same, skipping a token pass when its word is
 * absent. Guarded by {@link EmfExpressionParsing#ANIMATION_TEXT}: sampled calls run stock and
 * compare.
 */
public final class EmfAnimationText {
    private static final ThreadLocal<String> PENDING = new ThreadLocal<>();
    private static volatile Fields fields;

    private EmfAnimationText() {}

    private record Fields(Class<?> owner, Field id, Field part, Field originalPart) {}

    /** The prepared text, or null to run stock. */
    public static String process(Object part, String text) {
        PENDING.remove();
        EmfExpressionParsing.Guard guard = EmfExpressionParsing.ANIMATION_TEXT;
        if (part == null || text == null || !guard.enabled()) {
            return null;
        }
        String id;
        String partName;
        try {
            Fields f = fields;
            if (f == null || f.owner() != part.getClass()) {
                Class<?> owner = part.getClass();
                Field originalPart = owner.getDeclaredField("originalPart");
                if (!originalPart.trySetAccessible()) {
                    guard.disable("original_part_inaccessible");
                    return null;
                }
                f = new Fields(owner, owner.getField("id"), owner.getField("part"), originalPart);
                fields = f;
            }
            id = (String) f.id().get(part);
            String original = (String) f.originalPart().get(part);
            partName = original != null ? original : (String) f.part().get(part);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            guard.disable("part_fields_unavailable");
            return null;
        }
        if (id == null || partName == null) {
            return null; // Stock's own failure path.
        }
        String fast = EmfExpressionParsing.animationText(text, id, partName);
        if (guard.verifyThisCall()) {
            PENDING.set(fast);
            return null;
        }
        return fast;
    }

    /** At the end of a stock call that {@link #process} chose to verify. */
    public static void check(String stock) {
        String expected = PENDING.get();
        if (expected == null) {
            return;
        }
        PENDING.remove();
        if (expected.equals(stock)) {
            EmfExpressionParsing.ANIMATION_TEXT.confirmed();
        } else {
            EmfExpressionParsing.ANIMATION_TEXT.mismatch("text_differs");
        }
    }
}
