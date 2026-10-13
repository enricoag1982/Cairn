package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Text for the Javadoc of generated members. What the documents say goes in escaped, so that any text is safe in a
 * comment: markup, an at sign, a backslash (which Java reads as a Unicode escape), the end of the comment and non-ASCII
 * characters (the source does not depend on the encoding of the compiler).
 */
final class Docs {

    private Docs() {
    }

    /** Escapes text for a Javadoc comment, and puts it on one line. */
    static String text(String raw) {
        StringBuilder escaped = new StringBuilder();
        raw.strip().replaceAll("\\s+", " ").codePoints().forEach(c -> {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '@' -> escaped.append("&#64;");
                case '\\' -> escaped.append("&#92;");
                case '/' -> escaped.append(escaped.length() > 0 && escaped.charAt(escaped.length() - 1) == '*'
                        ? "&#47;" : "/");
                default -> {
                    if (c < 32 || c > 126) {
                        escaped.append("&#").append(c).append(';');
                    } else {
                        escaped.appendCodePoint(c);
                    }
                }
            }
        });
        return escaped.toString();
    }

    /** Text in code font. */
    static String code(String raw) {
        return "<code>" + text(raw) + "</code>";
    }

    /** Escapes text for a Java string literal. */
    static String literal(String raw) {
        StringBuilder escaped = new StringBuilder("\"");
        raw.codePoints().forEach(c -> {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 32 || c > 126) {
                        for (char unit : Character.toChars(c)) {
                            escaped.append(String.format("\\u%04x", (int) unit));
                        }
                    } else {
                        escaped.appendCodePoint(c);
                    }
                }
            }
        });
        return escaped.append('"').toString();
    }

    /**
     * The facts about an attribute that its Javadoc states besides its description: allowed values, pattern, lengths,
     * range and uniqueness. Each is a paragraph.
     */
    static List<String> constraints(Attribute attribute, String things) {
        List<String> paragraphs = new ArrayList<>();
        if (!attribute.allowedValues().isEmpty()) {
            paragraphs.add("<p>Allowed values: " + attribute.allowedValues().stream().map(Docs::code)
                    .collect(Collectors.joining(", ")) + ".");
        }
        attribute.pattern().ifPresent(pattern ->
                paragraphs.add("<p>Pattern (I-Regexp), which the whole value must match: " + code(pattern) + "."));
        if (attribute.minLength().isPresent() && attribute.maxLength().isPresent()) {
            paragraphs.add("<p>Length in Unicode code points: from " + attribute.minLength().getAsInt() + " to "
                    + attribute.maxLength().getAsInt() + ".");
        } else if (attribute.minLength().isPresent()) {
            paragraphs.add("<p>Length in Unicode code points: at least " + attribute.minLength().getAsInt() + ".");
        } else if (attribute.maxLength().isPresent()) {
            paragraphs.add("<p>Length in Unicode code points: at most " + attribute.maxLength().getAsInt() + ".");
        }
        attribute.range().ifPresent(range -> {
            List<String> bounds = new ArrayList<>();
            range.lower().ifPresent(bound -> bounds.add((bound.inclusive() ? "at least " : "greater than ")
                    + code(boundText(bound))));
            range.upper().ifPresent(bound -> bounds.add((bound.inclusive() ? "at most " : "less than ")
                    + code(boundText(bound))));
            paragraphs.add("<p>Range: " + String.join(", ", bounds) + ".");
        });
        if (attribute.unique()) {
            paragraphs.add("<p>Unique: no two " + things + " have the same value at the same time.");
        }
        return paragraphs;
    }

    private static String boundText(Bound bound) {
        return switch (bound.value()) {
            case Value.StringValue value -> value.value();
            case Value.BooleanValue value -> Boolean.toString(value.value());
            case Value.IntValue value -> Integer.toString(value.value());
            case Value.LongValue value -> Long.toString(value.value());
            case Value.DecimalValue value -> value.value().toPlainString();
            case Value.DateValue value -> value.value().toString();
            case Value.InstantValue value -> value.value().toString();
        };
    }
}
