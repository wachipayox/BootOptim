import dev.wachipayox.bootoptim.optimization.StrictPathSegmentValidator;
import java.util.Random;
import java.util.regex.Pattern;

/** Standalone exact grammar/guard contract; no Minecraft launch or duplicated runtime implementation. */
public class StrictPathSegmentContract {
    private static final Pattern STOCK = Pattern.compile("[-._a-z0-9]+");
    private static long checks;
    private static void check(String value) {
        boolean expected = STOCK.matcher(value).matches();
        if (expected != StrictPathSegmentValidator.matches(value)
                || expected != StrictPathSegmentValidator.guarded(STOCK, value)) throw new AssertionError(value);
        checks++;
    }
    public static void main(String[] args) {
        check(""); check("."); check(".."); check("models/item"); check("cafe\u0301");
        for (int c = 0; c <= 65535; c++) check(String.valueOf((char)c));
        for (int a = 0; a < 128; a++) for (int b = 0; b < 128; b++) check("" + (char)a + (char)b);
        Random random = new Random(20261003);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-._/ABCDEFGHIJKLMNOPQRSTUVWXYZ ";
        for (int n = 0; n < 50_000; n++) {
            StringBuilder text = new StringBuilder();
            for (int i = random.nextInt(128); i > 0; i--)
                text.append(n % 2 == 0 ? alphabet.charAt(random.nextInt(alphabet.length())) : (char)random.nextInt(65536));
            check(text.toString());
        }
        check("a".repeat(100_000)); check("a".repeat(99_999) + "\n");
        for (Pattern changed : new Pattern[] {Pattern.compile(".*"), Pattern.compile("[-._a-z0-9]+", Pattern.CASE_INSENSITIVE)}) {
            if (StrictPathSegmentValidator.compatible(changed)) throw new AssertionError("shape guard");
            if (StrictPathSegmentValidator.guarded(changed, "UPPER") != changed.matcher("UPPER").matches()) throw new AssertionError("fallback");
            checks++;
        }
        if (!StrictPathSegmentValidator.compatible(STOCK) || StrictPathSegmentValidator.compatible(null)) throw new AssertionError("guard");
        try { StrictPathSegmentValidator.guarded(STOCK, null); throw new AssertionError("null accepted"); }
        catch (NullPointerException expected) { checks++; }
        System.out.println("STRICT_PATH_SEGMENT_CONTRACT PASS checks=" + checks);
    }
}
