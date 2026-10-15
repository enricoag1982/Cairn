package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Quoted;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;

/**
 * Renders a {@linkplain WrittenNode tree} as YAML 1.2 in block style (design D7).
 *
 * <p>Each level is indented by two spaces, and the items of a sequence are indented below their key. The only text in
 * flow style is an empty mapping, {@code {}}, which has no block form. A string is plain unless
 * {@linkplain Quoting#isPlainSafe that would change what it reads as}, and then it is in double quotes. Keys are
 * written like strings.
 */
final class YamlText {

    private static final int INDENT = 2;

    private YamlText() {
    }

    /**
     * Renders a document.
     *
     * @param root the document's mapping
     * @return the YAML text, ending in a line break
     */
    static String render(Mapping root) {
        StringBuilder out = new StringBuilder();
        mapping(out, root, 0, false);
        return out.toString();
    }

    /**
     * Writes the members of a mapping, one per line.
     *
     * @param firstInline {@code true} if the first member continues the line that the caller has begun, as the first
     *                    member of a mapping in a sequence does after {@code "- "}
     */
    private static void mapping(StringBuilder out, Mapping mapping, int indent, boolean firstInline) {
        boolean first = true;
        for (Mapping.Member member : mapping.members()) {
            if (!(first && firstInline)) {
                spaces(out, indent);
            }
            first = false;
            out.append(key(member.key())).append(':');
            value(out, member.value(), indent);
        }
    }

    /** Writes what follows {@code key:}. */
    private static void value(StringBuilder out, WrittenNode value, int indent) {
        switch (value) {
            case Mapping mapping when mapping.isEmpty() -> out.append(" {}\n");
            case Mapping mapping -> {
                out.append('\n');
                mapping(out, mapping, indent + INDENT, false);
            }
            case Sequence sequence when sequence.items().isEmpty() -> out.append(" []\n");
            case Sequence sequence -> {
                out.append('\n');
                for (WrittenNode item : sequence.items()) {
                    item(out, item, indent + INDENT);
                }
            }
            case Text _, Quoted _, Plain _ -> out.append(' ').append(scalar(value)).append('\n');
        }
    }

    /** Writes one item of a sequence. */
    private static void item(StringBuilder out, WrittenNode item, int indent) {
        spaces(out, indent);
        out.append("- ");
        switch (item) {
            case Mapping mapping when mapping.isEmpty() -> out.append("{}\n");
            case Mapping mapping -> mapping(out, mapping, indent + INDENT, true);
            case Sequence _ -> throw new IllegalStateException("a sequence of sequences is not part of schema documents");
            case Text _, Quoted _, Plain _ -> out.append(scalar(item)).append('\n');
        }
    }

    private static String scalar(WrittenNode node) {
        return switch (node) {
            case Text text -> string(text.value());
            case Quoted quoted -> Quoting.doubleQuoted(quoted.value());
            case Plain plain -> plain.text();
            case Mapping _, Sequence _ -> throw new IllegalStateException("not a scalar: " + node);
        };
    }

    private static String key(String key) {
        return string(key);
    }

    private static String string(String text) {
        return Quoting.isPlainSafe(text) ? text : Quoting.doubleQuoted(text);
    }

    private static void spaces(StringBuilder out, int count) {
        out.append(" ".repeat(count));
    }
}
