package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Cheaper equivalents for Entity Model Features' animation-expression parsing, which runs for every
 * entity model on each resource reload (initial load and F3+T). Ported from the RRLS experiments of
 * 2026-09-27 (expression parse about -40 %, animation-text preparation about -110 ms per reload on
 * the fast PC), without the exact-version gate.
 *
 * <p>Each replaced algorithm has its own {@link Guard}: the first {@value #WARMUP} calls and one in
 * {@value #SAMPLE} afterwards run the original EMF code too and compare the results; a mismatch
 * disables that part and logs {@code [WBOMBO-COMPAT] FALLBACK}. Replacing {@code String.matches}
 * with a cached {@link Pattern} needs no guard: the JDK defines them as identical. Kill switch:
 * {@code -Dboot_optim.emfExpressionParsing=false}.</p>
 */
public final class EmfExpressionParsing {
    public static final String PROPERTY = "boot_optim.emfExpressionParsing";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    private static final String MOD_ID = "entity_model_features";
    private static final int WARMUP = 64;
    private static final long SAMPLE = 512L;
    private static final int MAX_PATTERNS = 64;

    public static final Guard NUMERIC_PROBE = new Guard("emf-parser-numeric-probe");
    public static final Guard CHARACTERS = new Guard("emf-parser-characters");
    public static final Guard OPERATOR_PASS = new Guard("emf-parser-operator-pass");
    public static final Guard METHOD_ARGUMENTS = new Guard("emf-method-arguments");
    public static final Guard VARIABLE_INDEX = new Guard("emf-asm-variable-index");
    public static final Guard VARIABLE_VALIDATION = new Guard("emf-asm-variable-validation");
    public static final Guard LINE_INDEX = new Guard("emf-animation-line-index");
    public static final Guard ANIMATION_TEXT = new Guard("emf-animation-text");
    public static final Guard DIRECTORY_SUFFIX = new Guard("emf-directory-suffix");

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    // Identical to EMFJemData's WHITESPACE/THIS/PART patterns; checked by ANIMATION_TEXT.
    private static final Pattern THIS = Pattern.compile("(?<=\\W|^)this(?=\\W)");
    private static final Pattern PART = Pattern.compile("(?<=\\W|^)part(?=\\W)");

    private EmfExpressionParsing() {}

    /** Per-part differential guard. */
    public static final class Guard {
        private final String mechanism;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong verified = new AtomicLong();
        private volatile boolean disabled = !ENABLED;

        private Guard(String mechanism) {
            this.mechanism = mechanism;
        }

        public boolean enabled() {
            return !disabled;
        }

        /** True when this call must also run the original code and be compared. */
        public boolean verifyThisCall() {
            long n = calls.incrementAndGet();
            return verified.get() < WARMUP || n % SAMPLE == 0;
        }

        public void confirmed() {
            if (verified.incrementAndGet() == WARMUP) {
                CompatLog.active(mechanism, MOD_ID);
            }
        }

        public void mismatch(String detail) {
            disable("result_mismatch " + detail);
        }

        public void disable(String reason) {
            if (!disabled) {
                disabled = true;
                CompatLog.fallback(mechanism, MOD_ID, reason);
            }
        }
    }

    /** {@code input.matches(regex)}, with the compiled pattern reused. */
    public static boolean matches(String input, String regex) {
        Pattern pattern = PATTERNS.get(regex);
        if (pattern == null) {
            pattern = Pattern.compile(regex);
            if (PATTERNS.size() < MAX_PATTERNS) {
                PATTERNS.putIfAbsent(regex, pattern);
            }
        }
        return pattern.matcher(input).matches();
    }

    public static boolean patternCacheEnabled() {
        return ENABLED;
    }

    /**
     * True when {@code Float.parseFloat(token)} certainly throws: after optional leading whitespace
     * and sign, the first character cannot start a decimal, hex, NaN or Infinity literal.
     */
    public static boolean definitelyNotNumber(String token) {
        if (token == null) {
            return false;
        }
        int start = 0;
        while (start < token.length() && token.charAt(start) <= ' ') {
            start++;
        }
        if (start == token.length()) {
            return false;
        }
        char first = token.charAt(start);
        if (first == '+' || first == '-') {
            if (++start == token.length()) {
                return false;
            }
            first = token.charAt(start);
        }
        return !(first >= '0' && first <= '9') && first != '.' && first != 'N' && first != 'I';
    }

    /** The exception EMF catches to treat the token as a variable, without a stack trace. */
    public static NumberFormatException notANumber() {
        return new NotANumber();
    }

    private static final class NotANumber extends NumberFormatException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** Iterates the UTF-16 code units of a string, like {@code s.chars().mapToObj(c -> (char) c)}. */
    public static Iterator<Character> characters(String text) {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < text.length();
            }

            @Override
            public Character next() {
                if (index >= text.length()) {
                    throw new NoSuchElementException();
                }
                return text.charAt(index++);
            }
        };
    }

    /** Drains an iterator, checks it yields exactly {@code text}, and returns a replay of it. */
    public static Iterator<Character> verifyCharacters(String text, Iterator<?> stock) {
        List<Character> seen = new ArrayList<>(text.length());
        while (stock.hasNext()) {
            seen.add((Character) stock.next());
        }
        boolean same = seen.size() == text.length();
        for (int i = 0; same && i < seen.size(); i++) {
            same = seen.get(i) == text.charAt(i);
        }
        if (same) {
            CHARACTERS.confirmed();
        } else {
            CHARACTERS.mismatch("characters_differ_from_expression");
        }
        return seen.iterator();
    }

    /** True when none of {@code actions} occurs in {@code components} (the stock pass is then a no-op). */
    public static boolean noneOf(List<?> actions, List<?> components) {
        for (int i = 0; i < actions.size(); i++) {
            if (components.contains(actions.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** EMF {@code MathMethod.getArgsList}: split on top-level commas, honouring brackets and escapes. */
    public static List<String> splitArguments(String args) {
        List<String> result = new ArrayList<>();
        int brackets = 0;
        StringBuilder builder = new StringBuilder();
        char last = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (last == '\\') {
                builder.append(ch);
                last = 0;
                continue;
            }
            if (ch == '(') {
                brackets++;
            } else if (ch == ')') {
                brackets--;
            } else if (ch == ',' && brackets == 0) {
                result.add(builder.toString().trim());
                builder.setLength(0);
                continue;
            }
            builder.append(ch);
            last = ch;
        }
        if (!builder.isEmpty()) {
            result.add(builder.toString().trim());
        }
        return result;
    }

    /**
     * EMF {@code EMFJemData.processAnimAndKeyString}: trim, drop {@code \s} characters, then replace
     * the {@code this} and {@code part} tokens, skipping each regex when its word is absent.
     */
    public static String animationText(String text, String id, String part) {
        String value = text.trim();
        StringBuilder clean = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean whitespace = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
            if (whitespace) {
                if (clean == null) {
                    clean = new StringBuilder(value.length()).append(value, 0, i);
                }
            } else if (clean != null) {
                clean.append(c);
            }
        }
        if (clean != null) {
            value = clean.toString();
        }
        if (value.contains("this")) {
            value = THIS.matcher(value).replaceAll(id);
        }
        // The id may itself introduce a "part" token, so test the result of the first pass.
        if (value.contains("part")) {
            value = PART.matcher(value).replaceAll(part);
        }
        return value;
    }

    /**
     * {@code path.replaceFirst(literal + "$", replacement)} for a plain-word literal and a replacement
     * without {@code $} or {@code \}; null when the call is outside that shape (use stock).
     */
    public static String replaceLiteralSuffix(String path, String regex, String replacement) {
        if (path == null || regex == null || replacement == null || regex.length() < 2
                || regex.charAt(regex.length() - 1) != '$'
                || replacement.indexOf('$') >= 0 || replacement.indexOf('\\') >= 0) {
            return null;
        }
        int length = regex.length() - 1;
        for (int i = 0; i < length; i++) {
            char c = regex.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return null;
            }
        }
        int end = path.length();
        // Regex $ also matches before a final line terminator; leave those to stock.
        if (end > 0) {
            char lastChar = path.charAt(end - 1);
            if (lastChar == '\n' || lastChar == '\r' || lastChar == '\u0085' || lastChar == ' ' || lastChar == ' ') {
                return null;
            }
        }
        String literal = regex.substring(0, length);
        return path.endsWith(literal) ? path.substring(0, end - length) + replacement : path;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
