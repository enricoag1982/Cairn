package io.github.enricoag1982.cairn.documents;

import java.util.HexFormat;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * How strings are written (design D7): in double quotes, with escapes that mean the same in JSON and in YAML 1.2, or,
 * in YAML, plain where that is safe.
 *
 * <p>A string is written plain only if it is made of characters that cannot be mistaken for YAML syntax, the YAML 1.2
 * core schema, which the reader uses too, resolves it to a string rather than to a number, a boolean or null, and it is
 * no word that YAML 1.1 tools read as a boolean ({@code yes}, {@code no}, {@code on}, {@code off}, {@code y} and
 * {@code n}), so that those tools read it as a string as well.
 */
final class Quoting {

    private static final ScalarResolver RESOLVER = new CoreSchema().getScalarResolver();
    private static final HexFormat HEX = HexFormat.of();

    /**
     * The words that YAML 1.1 reads as a boolean. The ones that YAML 1.2 reads as a boolean or as null too ({@code true},
     * {@code false}, {@code null}, {@code ~}) are quoted because of the core schema.
     */
    private static final Set<String> YAML_1_1_BOOLEANS = Set.of(
            "y", "Y", "yes", "Yes", "YES", "n", "N", "no", "No", "NO",
            "true", "True", "TRUE", "false", "False", "FALSE", "on", "On", "ON", "off", "Off", "OFF");

    /** The characters other than letters and digits that a plain string may contain. */
    private static final String PLAIN_PUNCTUATION = " _-.,()/+=;:'?!%&*@$<>^";

    private Quoting() {
    }

    /**
     * Tells whether a string can be written without quotes in YAML block style and be read back as the same string.
     *
     * @param text the string
     * @return {@code true} if it can
     */
    static boolean isPlainSafe(String text) {
        if (text.isEmpty() || !startsPlain(text.codePointAt(0)) || text.endsWith(" ") || text.endsWith(":")
                || text.contains(": ")) {
            return false;
        }
        boolean safe = text.codePoints().allMatch(Quoting::isPlainCharacter);
        return safe && !YAML_1_1_BOOLEANS.contains(text) && Tag.STR.equals(RESOLVER.resolve(text, true));
    }

    private static boolean startsPlain(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isPlainCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint < 0x80 && PLAIN_PUNCTUATION.indexOf(codePoint) >= 0;
    }

    /**
     * Writes a string in double quotes. The quote, the backslash and every character that is not printable in YAML or
     * would end a line in some readers are escaped; everything else is kept as it is. The escapes are those that JSON
     * and YAML 1.2 share.
     *
     * @param text the string
     * @return the quoted string
     */
    static String doubleQuoted(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        text.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (mustEscape(codePoint)) {
                        out.append("\\u").append(HEX.toHexDigits((char) codePoint));
                    } else {
                        out.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return out.append('"').toString();
    }

    /**
     * Tells whether a character cannot be written as it is in a quoted string: a control character, a character that
     * YAML does not print, a line or paragraph separator, the byte order mark and an unpaired surrogate.
     */
    private static boolean mustEscape(int codePoint) {
        return codePoint < 0x20
                || codePoint >= 0x7F && codePoint <= 0x9F
                || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint == 0xFEFF || codePoint == 0xFFFE || codePoint == 0xFFFF
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }
}
