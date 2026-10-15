package io.github.enricoag1982.cairn.core.schema;

import java.util.Optional;
import java.util.Set;

/**
 * Checks a pattern against the grammar of I-Regexp (RFC 9485), working on Unicode code points (ADR-0020, design D4).
 *
 * <p>The checker follows the RFC's ABNF, including its additional restriction that a character class must not be
 * {@code [^]}, and reports the first place where a pattern breaks it. It also rejects a range quantifier whose
 * minimum exceeds its maximum, and a character range whose start is after its end. In I-Regexp, {@code ^} and
 * {@code $} are ordinary characters outside a character class; there are no anchors. Unpaired surrogates are not
 * characters and are rejected.
 *
 * <p>Some valid I-Regexp cannot be matched by the engine that runs patterns (RE2/J, see {@link IRegexp}). The checker
 * rejects these too, so that a pattern which passes can be matched: the category {@code Cn}, which RE2/J does not
 * know; and repetition counts above {@value #MAX_REPEAT}. RE2/J also compiles and runs patterns with recursion that
 * is as deep as the pattern is long in a few respects, and it allocates for every element, so patterns of unbounded
 * complexity can exhaust the stack or the heap. Three limits keep patterns well inside what it can take, counted
 * after repetitions are written out: at most {@value #MAX_SIZE} elements (characters, classes, escapes, groups and
 * alternatives), at most {@value #MAX_BRANCHING} places where matching can go either way (groups, alternatives and
 * optional or open-ended repetitions), and groups nested at most {@value #MAX_NESTING} deep.
 */
final class IRegexpSyntax {

    /** The largest count in a range quantifier that the matching engine accepts. */
    static final int MAX_REPEAT = 1000;

    /** The most elements a pattern may expand to once its repetitions are written out. */
    static final long MAX_SIZE = 10_000;

    /**
     * The most places where matching can go either way that a pattern may expand to: groups, alternatives, and every
     * optional or open-ended copy of a repetition. RE2/J recurses once for each of them in a row.
     */
    static final long MAX_BRANCHING = 500;

    /** The deepest nesting of groups. */
    static final int MAX_NESTING = 100;

    /** The Unicode general categories of RFC 9485, which {@code \p{...}} and {@code \P{...}} accept. */
    private static final Set<String> CATEGORIES = Set.of(
            "L", "Ll", "Lm", "Lo", "Lt", "Lu",
            "M", "Mc", "Me", "Mn",
            "N", "Nd", "Nl", "No",
            "P", "Pc", "Pd", "Pe", "Pf", "Pi", "Po", "Ps",
            "Z", "Zl", "Zp", "Zs",
            "S", "Sc", "Sk", "Sm", "So",
            "C", "Cc", "Cf", "Cn", "Co");

    private static final String ESCAPES = "one of the escapes \\( \\) \\* \\+ \\- \\. \\? \\[ \\\\ \\] \\^ \\{ \\| \\} "
            + "\\n \\r \\t, or \\p{...} or \\P{...} with a Unicode category";

    /**
     * A place where a pattern breaks the grammar or a limit of the matching engine.
     *
     * @param index    the position of the problem, counted in code points from 0
     * @param reason   what is wrong, as a phrase that continues a sentence
     * @param expected what the grammar allows there
     * @param found    what the pattern has there, with its position
     * @param hint     how to fix it, if known
     */
    record Violation(int index, String reason, String expected, String found, Optional<String> hint) {
    }

    /**
     * What a part of a pattern costs the matching engine, counted after repetitions are written out.
     *
     * @param size      the number of elements
     * @param branching the number of places where matching can go either way
     */
    private record Cost(long size, long branching) {
        static final Cost NONE = new Cost(0, 0);
        static final Cost ELEMENT = new Cost(1, 0);

        Cost plus(Cost other) {
            return new Cost(size + other.size, branching + other.branching);
        }

        Cost times(long copies) {
            return new Cost(size * copies, branching * copies);
        }
    }

    /**
     * How a quantifier repeats its atom.
     *
     * @param copies   the number of copies of the atom, at least 1
     * @param optional the number of places where matching can go either way because a copy is optional or repeats
     */
    private record Repeat(long copies, long optional) {
    }

    /** Stops the parse at the first violation. It never leaves this class. */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Violation violation;

        Stop(Violation violation) {
            super(violation.reason(), null, false, false);
            this.violation = violation;
        }
    }

    private final int[] cps;
    private int pos;

    private IRegexpSyntax(int[] cps) {
        this.cps = cps;
    }

    /**
     * Checks a pattern.
     *
     * @param pattern the pattern in I-Regexp
     * @return the first violation, or empty if the pattern is valid and the matching engine can take it
     */
    static Optional<Violation> check(String pattern) {
        IRegexpSyntax syntax = new IRegexpSyntax(pattern.codePoints().toArray());
        try {
            syntax.parseAll();
            return Optional.empty();
        } catch (Stop stop) {
            return Optional.of(stop.violation);
        }
    }

    // ------------------------------------------------------------------ i-regexp, branch, piece, atom

    private void parseAll() {
        parseRegexp(0);
        if (pos < cps.length) { // only an unmatched ")" can stop the parse early
            throw stop(pos, "there is no group to close", "a character, '|' or the end of the pattern", foundAt(pos),
                    literalHint(cps[pos]));
        }
    }

    /** {@code i-regexp = branch *( "|" branch )}. Returns the cost. */
    private Cost parseRegexp(int depth) {
        Cost cost = parseBranch(depth);
        while (peek() == '|') {
            pos++;
            cost = limited(cost.plus(parseBranch(depth)).plus(new Cost(1, 1)), pos);
        }
        return cost;
    }

    /** {@code branch = *piece}. Returns the cost. */
    private Cost parseBranch(int depth) {
        Cost cost = Cost.NONE;
        while (pos < cps.length && cps[pos] != '|' && cps[pos] != ')') {
            cost = limited(cost.plus(parsePiece(depth)), pos);
        }
        return cost;
    }

    /** {@code piece = atom [ quantifier ]}. Returns the cost. */
    private Cost parsePiece(int depth) {
        Cost atom = parseAtom(depth);
        int quantifier = pos;
        Repeat repeat;
        int c = peek();
        if (c == '*' || c == '+' || c == '?') {
            pos++;
            repeat = new Repeat(1, 1);
        } else if (c == '{') {
            repeat = parseRangeQuantifier();
        } else {
            return atom;
        }
        if (isQuantifier(peek())) {
            throw stop(pos, "a quantifier cannot follow another quantifier",
                    "a character, escape, class or group, or the end of the branch", foundAt(pos),
                    Optional.of("Every quantifier is greedy, and patterns match the whole value, so lazy quantifiers "
                            + "are not needed. To repeat a repetition, group it: (a+)?"));
        }
        return limited(atom.times(repeat.copies()).plus(new Cost(0, repeat.optional())), quantifier);
    }

    /** {@code atom = NormalChar / charClass / ( "(" i-regexp ")" )}. Returns the cost. */
    private Cost parseAtom(int depth) {
        int c = cps[pos];
        return switch (c) {
            case '(' -> parseGroup(depth);
            case '[' -> {
                parseClass();
                yield Cost.ELEMENT;
            }
            case '\\' -> {
                parseEscape(false);
                yield Cost.ELEMENT;
            }
            case '.' -> {
                pos++;
                yield Cost.ELEMENT;
            }
            case '*', '+', '?', '{' -> throw quantifierWithoutAtom();
            case ']' -> throw stop(pos, "']' only closes a character class", "a character, escape, class or group",
                    foundAt(pos), literalHint(c));
            case '}' -> throw stop(pos, "'}' only closes a repetition", "a character, escape, class or group",
                    foundAt(pos), literalHint(c));
            default -> {
                requireScalar(c);
                pos++;
                yield Cost.ELEMENT;
            }
        };
    }

    private Cost parseGroup(int depth) {
        int open = pos;
        if (depth + 1 > MAX_NESTING) {
            throw stop(open, "groups are nested more than " + MAX_NESTING + " deep, which the matching engine does "
                    + "not take", "at most " + MAX_NESTING + " nested groups", foundAt(open), Optional.empty());
        }
        pos++;
        Cost inner = parseRegexp(depth + 1);
        if (pos >= cps.length) {
            throw stop(pos, "the group opened at index " + open + " is not closed", "')' to close the group", foundAt(pos),
                    Optional.of("Add ), or write \\( to match a literal '('."));
        }
        pos++; // the closing ")"
        return limited(inner.plus(new Cost(1, 1)), open);
    }

    private Stop quantifierWithoutAtom() {
        int c = cps[pos];
        Optional<String> hint = c == '?' && pos > 0 && cps[pos - 1] == '('
                ? Optional.of("Group modifiers such as (?:...) are not supported; write a plain group (...).")
                : Optional.of("A quantifier repeats the character, class or group before it. "
                        + literalHint(c).orElseThrow());
        return stop(pos, "a quantifier has nothing to repeat", "a character, escape, character class or group",
                foundAt(pos), hint);
    }

    /** {@code range-quantifier = "{" QuantExact [ "," [ QuantExact ] ] "}"}. */
    private Repeat parseRangeQuantifier() {
        int open = pos;
        pos++;
        long minimum = parseCount();
        long maximum = minimum;
        String expected = "',' or '}'";
        if (peek() == ',') {
            pos++;
            if (isDigit(peek())) {
                maximum = parseCount();
                expected = "'}'";
            } else {
                maximum = -1;
                expected = "a number or '}'";
            }
        }
        if (peek() != '}') {
            throw stop(pos, "the repetition opened at index " + open + " is not closed", expected, foundAt(pos),
                    literalHint('{'));
        }
        pos++;
        if (maximum >= 0 && minimum > maximum) {
            throw stop(open, "the minimum " + minimum + " of the repetition is greater than its maximum " + maximum,
                    "a minimum that is not greater than the maximum", "{" + minimum + "," + maximum + "} at index " + open,
                    Optional.empty());
        }
        long largest = Math.max(minimum, maximum);
        if (largest > MAX_REPEAT) {
            throw stop(open, "a repetition count above " + MAX_REPEAT + " is not supported by the matching engine",
                    "a count of at most " + MAX_REPEAT, "the count " + largest + " at index " + open,
                    Optional.of("Use a smaller count, or split the pattern."));
        }
        if (maximum < 0) { // {n,}: n copies and a loop
            return new Repeat(minimum + 1, 1);
        }
        return new Repeat(Math.max(1, maximum), maximum - minimum);
    }

    /** {@code QuantExact = 1*%x30-39}. The value saturates, because every count above the limit is rejected anyway. */
    private long parseCount() {
        if (!isDigit(peek())) {
            throw stop(pos, "a repetition needs a number", "a number", foundAt(pos), literalHint('{'));
        }
        long value = 0;
        while (isDigit(peek())) {
            value = Math.min(value * 10 + (cps[pos] - '0'), Integer.MAX_VALUE);
            pos++;
        }
        return value;
    }

    // ------------------------------------------------------------------ escapes

    /**
     * Parses a {@code SingleCharEsc} or a {@code charClassEsc} at a backslash and returns the character that a single
     * character escape stands for, or -1 for a category.
     */
    private int parseEscape(boolean inClass) {
        int start = pos;
        if (pos + 1 >= cps.length) {
            throw stop(start, "the pattern ends with a backslash", "a character to escape",
                    "a backslash at the end of the pattern (index " + start + ")",
                    Optional.of("Write \\\\ to match a backslash."));
        }
        int c = cps[pos + 1];
        if (c == 'p' || c == 'P') {
            parseCategory();
            return -1;
        }
        int value = singleCharEscape(c);
        if (value < 0) {
            throw badEscape(start, c, inClass);
        }
        pos += 2;
        return value;
    }

    /** {@code SingleCharEsc}: the character an escape stands for, or -1 if {@code c} cannot follow a backslash. */
    private static int singleCharEscape(int c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '(', ')', '*', '+', '-', '.', '?', '[', '\\', ']', '^', '{', '|', '}' -> c;
            default -> -1;
        };
    }

    /** {@code catEsc = "\p{" charProp "}"} and {@code complEsc = "\P{" charProp "}"}. */
    private void parseCategory() {
        int start = pos;
        String escape = "\\" + (char) cps[pos + 1];
        pos += 2;
        if (peek() != '{') {
            throw stop(pos, escape + " must be followed by a category in braces",
                    "'{' and a category, such as " + escape + "{L}", foundAt(pos),
                    Optional.of("Write " + escape + "{L} with braces."));
        }
        int nameStart = pos + 1;
        StringBuilder name = new StringBuilder();
        pos++;
        while (pos < cps.length && cps[pos] != '}') {
            name.appendCodePoint(cps[pos]);
            pos++;
        }
        if (pos >= cps.length) {
            throw stop(pos, "the category after " + escape + " is not closed", "'}' to close the category",
                    foundAt(pos), Optional.empty());
        }
        String category = name.toString();
        if (!CATEGORIES.contains(category)) {
            throw stop(nameStart, "'" + category + "' is not a Unicode general category",
                    "a general category: L, Lu, Ll, Lm, Lo, Lt, M, Mn, Mc, Me, N, Nd, Nl, No, P, Pc, Pd, Ps, Pe, Pi, Pf, "
                            + "Po, Z, Zs, Zl, Zp, S, Sm, Sc, Sk, So, C, Cc, Cf, Cn or Co",
                    (category.isEmpty() ? "an empty category" : "'" + category + "'") + " at index " + nameStart,
                    Optional.of("Only general categories are supported, not scripts, blocks or names such as Alpha. "
                            + "Use \\p{L} for letters, \\p{Lu} for upper-case letters or \\p{Nd} for digits."));
        }
        pos++; // the closing "}"
        if (category.equals("Cn")) {
            throw stop(start, "the category Cn is valid I-Regexp, but the matching engine does not know it",
                    "a category other than Cn", escape + "{Cn} at index " + start,
                    Optional.of("Describe the characters with other categories instead."));
        }
    }

    private Stop badEscape(int start, int c, boolean inClass) {
        String escape = "\\" + new String(Character.toChars(c));
        boolean shortcut = "dDsSwWiIcC".indexOf(c) >= 0;
        String reason = shortcut
                ? "the shortcut " + escape + " is not supported"
                : escape + " is not an escape that I-Regexp knows";
        return stop(start, reason, ESCAPES, escape + " at index " + start, escapeHint(c, inClass));
    }

    /** Advice for an escape that I-Regexp does not have: the replacement from RFC 9485 for a shortcut. */
    private static Optional<String> escapeHint(int c, boolean inClass) {
        String escape = "\\" + new String(Character.toChars(c));
        return switch (c) {
            case 'd', 's', 'w' -> {
                String content = shortcutContent(c);
                String ascii = c == 'w' ? " (ASCII letters, digits and underscore)" : "";
                yield Optional.of(inClass
                        ? "Inside a character class, write " + content + " instead of " + escape + ascii + "."
                        : "Write [" + content + "] instead of " + escape + ascii + ".");
            }
            case 'D', 'S', 'W' -> {
                String content = shortcutContent(Character.toLowerCase(c));
                yield Optional.of(inClass
                        ? "A negated shortcut cannot be part of a character class. Use the negated class [^" + content
                                + "] on its own instead of " + escape + "."
                        : "Write [^" + content + "] instead of " + escape + ".");
            }
            case 'i', 'I', 'c', 'C' -> Optional.of(escape + " (XML name characters) is not supported. List the "
                    + "characters in a character class instead.");
            case 'b', 'B' -> Optional.of("Word boundaries are not supported. A pattern always matches the whole value.");
            case 'A', 'z', 'Z', 'G' -> Optional.of("Anchors are not supported. A pattern always matches the whole value.");
            case 'x', 'u', 'U' -> Optional.of("Write the character itself instead of an escape sequence.");
            case 'f', 'v', 'a', 'e' -> Optional.of("Only \\n, \\r and \\t are supported as escapes for control "
                    + "characters. Write the character itself.");
            default -> otherEscapeHint(c);
        };
    }

    private static Optional<String> otherEscapeHint(int c) {
        if (isDigit(c)) {
            return Optional.of("Back-references and numeric escapes are not supported.");
        }
        boolean asciiLetter = c < 128 && Character.isLetter(c);
        if (asciiLetter || isSurrogate(c)) {
            return Optional.empty();
        }
        return Optional.of("Write " + quote(c) + " without the backslash.");
    }

    /** What the shortcut {@code \d}, {@code \s} or {@code \w} covers, written as the inside of a character class. */
    private static String shortcutContent(int lowerCase) {
        return switch (lowerCase) {
            case 'd' -> "0-9";
            case 's' -> " \\t\\n\\r";
            default -> "a-zA-Z0-9_";
        };
    }

    // ------------------------------------------------------------------ character classes

    /**
     * {@code charClassExpr = "[" [ "^" ] ( "-" / CCE1 ) *CCE1 [ "-" ] "]"}, where {@code [^]} is not allowed.
     */
    private void parseClass() {
        int start = pos;
        pos++;
        if (peek() == '^') {
            if (peekAt(1) == ']') {
                throw stop(start, "[^] is not allowed, because it could be a negated empty class or a class of '^'",
                        "a character class with at least one entry", "[^] at index " + start,
                        Optional.of("Write \\^ or [\\^] to match a caret."));
            }
            pos++;
        }
        if (pos >= cps.length) {
            throw unterminatedClass(start);
        }
        if (peek() == '-') {
            pos++; // a leading hyphen
        } else if (peek() == ']') {
            throw stop(pos, "the character class is empty", "a character, range or escape in the class",
                    foundAt(pos), Optional.of("A class needs at least one entry. To match a literal ']', write \\]."));
        } else {
            parseClassEntry();
        }
        while (true) {
            if (pos >= cps.length) {
                throw unterminatedClass(start);
            }
            int c = cps[pos];
            if (c == ']') {
                pos++;
                return;
            }
            if (c == '-') {
                if (peekAt(1) != ']') {
                    throw stop(pos, "'-' is only allowed first, last or between the two ends of a range",
                            "a character, range or escape, or ']' to close the class", foundAt(pos),
                            Optional.of("Write \\- to match a literal hyphen."));
                }
                pos += 2; // a trailing hyphen and the end of the class
                return;
            }
            parseClassEntry();
        }
    }

    private Stop unterminatedClass(int start) {
        return stop(cps.length, "the character class opened at index " + start + " is not closed",
                "']' to close the character class", foundAt(cps.length),
                Optional.of("Add ], or write \\[ to match a literal '['."));
    }

    /** {@code CCE1 = ( CCchar [ "-" CCchar ] ) / charClassEsc}. */
    private void parseClassEntry() {
        int start = pos;
        if (cps[pos] == '\\' && (peekAt(1) == 'p' || peekAt(1) == 'P')) {
            parseCategory();
            return;
        }
        int from = parseClassChar();
        if (peek() != '-' || peekAt(1) == ']') {
            return;
        }
        pos++; // the hyphen of a range
        if (pos >= cps.length) {
            throw unterminatedClass(start);
        }
        if (cps[pos] == '\\' && (peekAt(1) == 'p' || peekAt(1) == 'P')) {
            throw stop(pos, "a range must end with a single character, not a category",
                    "a single character or escape", "\\" + (char) peekAt(1) + " at index " + pos, Optional.empty());
        }
        int to = parseClassChar();
        if (to < from) {
            String range = quote(from) + "-" + quote(to);
            throw stop(start, "the range " + range + " ends before it starts",
                    "a range from a smaller to a larger character", range + " at index " + start,
                    Optional.of("Swap the ends: " + quote(to) + "-" + quote(from) + "."));
        }
    }

    /** {@code CCchar}: one character, or a single character escape. Returns its code point. */
    private int parseClassChar() {
        int c = cps[pos];
        if (c == '\\') {
            return parseEscape(true);
        }
        if (c == '[') {
            throw stop(pos, "'[' inside a character class must be escaped", "a character or escape", foundAt(pos),
                    literalHint(c));
        }
        if (c == '-') {
            throw stop(pos, "'-' cannot be a range end", "a character or escape", foundAt(pos),
                    Optional.of("Write \\- to match a literal hyphen."));
        }
        requireScalar(c);
        pos++;
        return c;
    }

    // ------------------------------------------------------------------ helpers

    private int peek() {
        return peekAt(0);
    }

    /** The code point {@code offset} places ahead, or -1 at the end of the pattern. */
    private int peekAt(int offset) {
        int index = pos + offset;
        return index < cps.length ? cps[index] : -1;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isQuantifier(int c) {
        return c == '*' || c == '+' || c == '?' || c == '{';
    }

    private static boolean isSurrogate(int c) {
        return c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE;
    }

    /** Unpaired surrogates are not Unicode characters, and are in no production of the grammar. */
    private void requireScalar(int c) {
        if (isSurrogate(c)) {
            throw stop(pos, "an unpaired surrogate is not a character", "a Unicode character", foundAt(pos),
                    Optional.empty());
        }
    }

    /** Returns the cost if it is within the limits; otherwise stops the parse at an index. */
    private Cost limited(Cost cost, int index) {
        if (cost.size() > MAX_SIZE) {
            throw stop(index, "the pattern is too large for the matching engine: once repetitions are written out it "
                    + "has more than " + MAX_SIZE + " elements", "a pattern of at most " + MAX_SIZE + " elements once "
                    + "repetition counts are written out", "a pattern that is too large, noticed at index " + index,
                    Optional.of("Lower the repetition counts, or do not nest repetitions."));
        }
        if (cost.branching() > MAX_BRANCHING) {
            throw stop(index, "the pattern is too complex for the matching engine: once repetitions are written out it "
                    + "has more than " + MAX_BRANCHING + " groups, alternatives and optional repetitions",
                    "a pattern with at most " + MAX_BRANCHING + " groups, alternatives and optional repetitions once "
                            + "repetition counts are written out",
                    "a pattern that is too complex, noticed at index " + index,
                    Optional.of("Lower the repetition counts, do not nest repetitions, or use fewer groups. To limit "
                            + "the length of a value, use a minimum or maximum length instead of a repetition."));
        }
        return cost;
    }

    /** Advice for a character that is special in I-Regexp and was written where a literal character was meant. */
    private static Optional<String> literalHint(int c) {
        return Optional.of("To match a literal " + quote(c) + ", write \\" + new String(Character.toChars(c)) + ".");
    }

    private String foundAt(int index) {
        return index >= cps.length
                ? "the end of the pattern (index " + index + ")"
                : quote(cps[index]) + " at index " + index;
    }

    /** A code point in quotes, or as its number if it cannot be shown. */
    private static String quote(int c) {
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE ->
                    String.format("U+%04X", c);
            default -> "'" + new String(Character.toChars(c)) + "'";
        };
    }

    private static Stop stop(int index, String reason, String expected, String found, Optional<String> hint) {
        return new Stop(new Violation(index, reason, expected, found, hint));
    }
}
