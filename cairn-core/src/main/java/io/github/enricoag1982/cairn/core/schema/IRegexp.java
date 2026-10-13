package io.github.enricoag1982.cairn.core.schema;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.util.Objects;
import java.util.Optional;

/**
 * A pattern in I-Regexp (RFC 9485): checked against the RFC's grammar, matched in linear time with RE2/J, and
 * translated for other regular expression engines (ADR-0020, design D4).
 *
 * <p>I-Regexp has no anchors: a pattern matches a value as a whole, and {@code ^} and {@code $} are ordinary
 * characters. Every use of patterns goes through this class: the validator checks patterns and matches examples with
 * it, values will be matched with it at commit, and the JSON Schema export will publish {@link #ecmaScript()}.
 *
 * <p>The RFC translates an I-Regexp to other engines by replacing every unescaped {@code .} outside a character class
 * with {@code [^\n\r]}, and anchoring the result. Both translations here also write the characters {@code ^} and
 * {@code $} outside a character class as {@code \^} and {@code \$}, because RE2 and ECMAScript would otherwise take
 * them for anchors although I-Regexp means them literally. The ECMAScript translation also writes {@code \-} outside a
 * character class as {@code -}, because ECMAScript does not allow that escape in Unicode mode, and a consumer should
 * compile it with the {@code u} flag.
 */
public final class IRegexp {

    /** The result of parsing a pattern. */
    sealed interface Parsed {

        /**
         * A valid pattern.
         *
         * @param pattern the pattern
         */
        record Valid(IRegexp pattern) implements Parsed {
        }

        /**
         * A pattern that is not valid I-Regexp, or that the matching engine cannot take.
         *
         * @param violation where and how it breaks the grammar or a limit
         */
        record Invalid(IRegexpSyntax.Violation violation) implements Parsed {
        }
    }

    private final String source;
    private final String re2;
    private final String ecmaScript;
    private final Pattern compiled;

    /**
     * Compiles a pattern that {@link IRegexpSyntax} has accepted, and tries it once on the empty value, because RE2/J
     * recurses while matching as well as while compiling.
     */
    private IRegexp(String source) {
        int[] codePoints = source.codePoints().toArray();
        this.source = source;
        this.re2 = "\\A(?:" + translate(codePoints, false) + ")\\z";
        this.ecmaScript = "^(?:" + translate(codePoints, true) + ")$";
        this.compiled = Pattern.compile(re2);
        compiled.matcher("").matches();
    }

    /**
     * Checks a pattern and prepares it for matching.
     *
     * @param pattern the pattern in I-Regexp
     * @return the pattern, or where it is not valid
     */
    static Parsed parse(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        Optional<IRegexpSyntax.Violation> violation = IRegexpSyntax.check(pattern);
        if (violation.isPresent()) {
            return new Parsed.Invalid(violation.get());
        }
        try {
            return new Parsed.Valid(new IRegexp(pattern));
        } catch (PatternSyntaxException e) {
            return refused(e.getDescription());
        } catch (StackOverflowError e) {
            // The limits of IRegexpSyntax keep this away on stacks of the usual size; a very small one can still hit it.
            return refused("it needs more stack than is available");
        }
    }

    /** A pattern that the checker accepted and the matching engine nevertheless refused. */
    private static Parsed refused(String why) {
        return new Parsed.Invalid(new IRegexpSyntax.Violation(0, "the matching engine cannot take the pattern: " + why,
                "a pattern that the matching engine can compile", "a pattern that it refused",
                Optional.of("Simplify the pattern: use smaller repetition counts and fewer groups.")));
    }

    /**
     * Checks a pattern and prepares it for matching.
     *
     * @param pattern the pattern in I-Regexp
     * @return the pattern
     * @throws IllegalArgumentException if the pattern is not valid
     */
    public static IRegexp of(String pattern) {
        return switch (parse(pattern)) {
            case Parsed.Valid(IRegexp valid) -> valid;
            case Parsed.Invalid(IRegexpSyntax.Violation violation) -> throw new IllegalArgumentException(
                    "The pattern '" + pattern + "' is not a valid I-Regexp: " + violation.reason() + " (at index "
                            + violation.index() + ").");
        };
    }

    /**
     * Tells whether a value matches the pattern as a whole.
     *
     * @param value the value
     * @return {@code true} if the whole value matches
     */
    public boolean matches(CharSequence value) {
        return compiled.matcher(value).matches();
    }

    /**
     * The pattern as it was written.
     *
     * @return the I-Regexp
     */
    public String source() {
        return source;
    }

    /**
     * The pattern translated for RE2, anchored at both ends as {@code \A(?:...)\z}.
     *
     * @return the RE2 syntax
     */
    String re2() {
        return re2;
    }

    /**
     * The pattern translated for ECMAScript, anchored at both ends as {@code ^(?:...)$}, for use with the {@code u}
     * flag.
     *
     * @return the ECMAScript syntax
     */
    String ecmaScript() {
        return ecmaScript;
    }

    @Override
    public String toString() {
        return source;
    }

    /**
     * Applies the translation of RFC 9485 to a valid pattern: the dot becomes {@code [^\n\r]}. Outside character
     * classes, {@code ^} and {@code $} are escaped, and, for ECMAScript, {@code \-} is written as {@code -}.
     */
    private static String translate(int[] codePoints, boolean forEcmaScript) {
        StringBuilder out = new StringBuilder(codePoints.length + 16);
        boolean inClass = false;
        for (int i = 0; i < codePoints.length; i++) {
            int c = codePoints[i];
            if (c == '\\') {
                int next = codePoints[++i];
                if (forEcmaScript && !inClass && next == '-') {
                    out.append('-');
                    continue;
                }
                out.append('\\').appendCodePoint(next);
                if (next == 'p' || next == 'P') {
                    do {
                        out.appendCodePoint(codePoints[++i]); // the category, from its opening brace to its closing one
                    } while (codePoints[i] != '}');
                }
            } else if (inClass) {
                out.appendCodePoint(c);
                inClass = c != ']';
            } else if (c == '[') {
                out.append('[');
                inClass = true;
            } else if (c == '.') {
                out.append("[^\\n\\r]");
            } else if (c == '^' || c == '$') {
                out.append('\\').append((char) c);
            } else {
                out.appendCodePoint(c);
            }
        }
        return out.toString();
    }
}
