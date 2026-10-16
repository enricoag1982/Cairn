package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.NodeReader.Entry;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/**
 * Where the expression of a check or of a derived attribute is in a schema document (capability
 * {@code schema-documents}, design D6 of {@code add-cel-expressions} and D6 of {@code add-derived-attributes}): the key
 * {@code expression} of a check or {@code derived} of an attribute, and, where the text allows it, where each line of the
 * expression starts, so that a position in the expression, as the expression language reports it, becomes a position in
 * the document.
 *
 * <p>The text of an expression can be mapped to the document exactly when it is written as it is read: a plain scalar, a
 * quoted scalar without escapes, or a literal block scalar (<code>|</code>), each of which keeps the characters of a
 * line together. Any other way of writing it (a folded block, an expression on several plain or quoted lines, or escape
 * sequences) has no exact map, and a position in it is located at the key.
 *
 * @param key        the key {@code expression} or {@code derived}, with the pointer of the expression
 * @param lineStarts the position in the document of the first character of each line of the expression, if the text
 *                   can be mapped; empty otherwise
 */
record ExpressionPlace(DocumentLocation key, List<DocumentLocation> lineStarts) {

    /** The position that a problem about the expression gives, in the text of its message. */
    private static final Pattern POSITION = Pattern.compile("at line (\\d+), column (\\d+)");

    ExpressionPlace {
        Objects.requireNonNull(key, "key");
        lineStarts = List.copyOf(lineStarts);
    }

    /**
     * Finds where the expression is.
     *
     * @param codePoints the text of the document, as code points, which the marks of the nodes index
     * @param entry      the member {@code expression} of the check, or {@code derived} of the attribute
     * @param scalar     the value of the member
     */
    static ExpressionPlace of(int[] codePoints, Entry entry, ScalarNode scalar) {
        DocumentLocation key = Marks.location(entry.pointer(), entry.keyNode());
        return new ExpressionPlace(key, lineStarts(codePoints, entry.pointer(), scalar));
    }

    /**
     * Locates a problem about the expression: at the position in the expression that its message gives, if the document
     * lets the position be found, and otherwise at the key {@code expression}.
     *
     * @param message the message of the problem, which gives the position as {@code at line L, column C}
     */
    DocumentLocation locate(String message) {
        Matcher matcher = POSITION.matcher(message);
        if (!matcher.find()) {
            return key;
        }
        long line = Long.parseLong(matcher.group(1));
        long column = Long.parseLong(matcher.group(2));
        if (line < 1 || column < 1 || line > lineStarts.size()) {
            return key;
        }
        DocumentLocation start = lineStarts.get((int) line - 1);
        return new DocumentLocation(key.pointer(), start.line(), (int) Math.min(Integer.MAX_VALUE,
                start.column() + column - 1));
    }

    private static List<DocumentLocation> lineStarts(int[] codePoints, String pointer, ScalarNode scalar) {
        Optional<Mark> start = scalar.getStartMark();
        Optional<Mark> end = scalar.getEndMark();
        if (start.isEmpty() || end.isEmpty() || end.get().getIndex() > codePoints.length
                || start.get().getIndex() > end.get().getIndex()) {
            return List.of();
        }
        String raw = new String(codePoints, start.get().getIndex(), end.get().getIndex() - start.get().getIndex());
        String value = scalar.getValue();
        int line = start.get().getLine() + 1;
        int column = start.get().getColumn() + 1;
        ScalarStyle style = scalar.getScalarStyle();
        if (style == ScalarStyle.PLAIN) {
            return raw.equals(value) && value.indexOf('\n') < 0
                    ? List.of(new DocumentLocation(pointer, line, column)) : List.of();
        }
        if (style == ScalarStyle.DOUBLE_QUOTED || style == ScalarStyle.SINGLE_QUOTED) {
            char quote = style == ScalarStyle.DOUBLE_QUOTED ? '"' : '\'';
            boolean exact = raw.length() >= 2 && raw.charAt(0) == quote && raw.charAt(raw.length() - 1) == quote
                    && raw.substring(1, raw.length() - 1).equals(value) && value.indexOf('\n') < 0;
            return exact ? List.of(new DocumentLocation(pointer, line, column + 1)) : List.of();
        }
        if (style == ScalarStyle.LITERAL) {
            return literalLineStarts(pointer, raw, value, line);
        }
        return List.of();
    }

    /**
     * The lines of a literal block scalar: a header line, such as <code>|</code>, and then the lines of the value, each
     * indented by the same number of spaces.
     *
     * @param line the line of the document that the header is on
     */
    private static List<DocumentLocation> literalLineStarts(String pointer, String raw, String value, int line) {
        int header = raw.indexOf('\n');
        if (header < 0) {
            return List.of();
        }
        String[] content = raw.substring(header + 1).split("\n", -1);
        String[] lines = value.split("\n", -1);
        int indent = -1;
        for (String candidate : content) {
            if (!candidate.isBlank()) {
                indent = candidate.length() - candidate.stripLeading().length();
                break;
            }
        }
        if (indent < 0 || lines.length > content.length) {
            return List.of();
        }
        List<DocumentLocation> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String text = content[i];
            boolean matches = lines[i].isEmpty() ? text.isBlank()
                    : text.length() >= indent && text.substring(indent).equals(lines[i]);
            if (!matches) {
                return List.of();
            }
            starts.add(new DocumentLocation(pointer, line + 1 + i, indent + 1));
        }
        return starts;
    }
}
