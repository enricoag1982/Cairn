package io.github.enricoag1982.cairn.documents;

/**
 * Translates the patterns of attributes, which are I-Regexp (RFC 9485), into the ECMAScript regular expressions that
 * JSON Schema uses (ADR-0020, design D5 of {@code add-json-payloads}).
 *
 * <p>This is the translation of the engine, repeated here because this module does not depend on the engine: a test
 * compares the two on sample patterns. I-Regexp has no anchors and matches a value as a whole, so the result is anchored
 * as {@code ^(?:...)$}. Every unescaped {@code .} outside a character class becomes {@code [^\n\r]}, as the RFC says.
 * The characters {@code ^} and {@code $} outside a character class are ordinary characters in I-Regexp but anchors in
 * ECMAScript, so they are written as {@code \^} and {@code \$}. An escaped {@code \-} outside a character class is
 * written as {@code -}, because ECMAScript does not allow that escape with the {@code u} flag, with which the result is
 * meant to be compiled.
 */
final class EcmaScriptPatterns {

    private EcmaScriptPatterns() {
    }

    /**
     * Translates a pattern. The pattern is expected to be valid I-Regexp, as the schema registry checks the pattern of
     * every attribute; a pattern that is not is translated as far as it can be.
     *
     * @param iRegexp the pattern in I-Regexp
     * @return the pattern in ECMAScript, anchored at both ends
     */
    static String of(String iRegexp) {
        int[] codePoints = iRegexp.codePoints().toArray();
        StringBuilder out = new StringBuilder(codePoints.length + 16).append("^(?:");
        boolean inClass = false;
        for (int i = 0; i < codePoints.length; i++) {
            int c = codePoints[i];
            if (c == '\\') {
                if (i + 1 == codePoints.length) {
                    out.append('\\');
                    break;
                }
                int next = codePoints[++i];
                if (!inClass && next == '-') {
                    out.append('-');
                    continue;
                }
                out.append('\\').appendCodePoint(next);
                if (next == 'p' || next == 'P') {
                    // the category, from its opening brace to its closing one, which means nothing else
                    while (i + 1 < codePoints.length) {
                        out.appendCodePoint(codePoints[++i]);
                        if (codePoints[i] == '}') {
                            break;
                        }
                    }
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
        return out.append(")$").toString();
    }
}
