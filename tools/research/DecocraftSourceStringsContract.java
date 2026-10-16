import dev.wachipayox.bootoptim.optimization.client.ExactSourceStrings;
import java.util.List;

/** Host-only contract for ExactSourceStrings: no Minecraft, no Decocraft, no forced GC. */
public final class DecocraftSourceStringsContract {
    public static class Texture {
        public String source;

        Texture(String value) {
            source = value;
        }
    }

    public static class Bad {
        public StringBuilder source = new StringBuilder();
    }

    public static class ReadOnly {
        public final String source = "immutable";
    }

    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    public static void main(String[] args) throws Exception {
        String text = "data:image/png;base64," + "aBcDEF1234".repeat(1000);
        var first = new Texture(new String(text.toCharArray()));
        var second = new Texture(new String(text.toCharArray()));
        var untouched = new Texture("path/to/file");
        var empty = new Texture(null);
        String originalShort = untouched.source;
        check(first.source != second.source, "fixture must start with distinct instances");

        var result = ExactSourceStrings.share(List.of(first, second, first, untouched, empty));
        check(first.source == second.source, "equal text shares one instance");
        check(text.equals(first.source), "text unchanged");
        check(result.fields() == 2 && result.replaced() == 1, "counts: repeated object counted once");
        check(result.uniqueCharactersBefore() == 2L * text.length() && result.uniqueCharactersAfter() == text.length(), "character budget");
        check(untouched.source == originalShort && empty.source == null, "short and null sources untouched");
        check(first != second, "objects stay distinct");

        var repeated = ExactSourceStrings.share(List.of(first, second));
        check(repeated.replaced() == 0 && repeated.uniqueCharactersBefore() == repeated.uniqueCharactersAfter(), "idempotent");

        var good = new Texture(new String((text + "new").toCharArray()));
        String original = good.source;
        try {
            ExactSourceStrings.share(List.of(good, new Bad()));
            check(false, "wrong field type accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "wrong field type rejected");
        }
        check(good.source == original, "nothing mutated before validation completed");
        try {
            ExactSourceStrings.share(List.of(new ReadOnly()));
            check(false, "final field accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "final field rejected");
        }
        var changed = new Texture(text);
        changed.source = "other";
        ExactSourceStrings.share(List.of(changed));
        check("other".equals(changed.source), "short value stays");
        System.out.println("DECOCRAFT_SOURCE_STRINGS_CONTRACT PASS checks=" + checks);
    }
}
