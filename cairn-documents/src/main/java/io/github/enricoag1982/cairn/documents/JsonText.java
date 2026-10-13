package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Quoted;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import java.util.List;

/**
 * Renders a {@linkplain WrittenNode tree} as JSON with two-space indentation, one member or item per line (design D7).
 * Strings are always in quotes. JSON is valid YAML 1.2, so the reader reads the result like any other document.
 */
final class JsonText {

    private static final int INDENT = 2;

    private JsonText() {
    }

    /**
     * Renders a document.
     *
     * @param root the document's mapping
     * @return the JSON text, ending in a line break
     */
    static String render(Mapping root) {
        StringBuilder out = new StringBuilder();
        node(out, root, 0);
        return out.append('\n').toString();
    }

    private static void node(StringBuilder out, WrittenNode node, int indent) {
        switch (node) {
            case Mapping mapping when mapping.isEmpty() -> out.append("{}");
            case Mapping mapping -> {
                out.append("{\n");
                List<Mapping.Member> members = mapping.members();
                for (int i = 0; i < members.size(); i++) {
                    spaces(out, indent + INDENT);
                    out.append(Quoting.doubleQuoted(members.get(i).key())).append(": ");
                    node(out, members.get(i).value(), indent + INDENT);
                    out.append(i < members.size() - 1 ? ",\n" : "\n");
                }
                spaces(out, indent);
                out.append('}');
            }
            case Sequence sequence when sequence.items().isEmpty() -> out.append("[]");
            case Sequence sequence -> {
                out.append("[\n");
                for (int i = 0; i < sequence.items().size(); i++) {
                    spaces(out, indent + INDENT);
                    node(out, sequence.items().get(i), indent + INDENT);
                    out.append(i < sequence.items().size() - 1 ? ",\n" : "\n");
                }
                spaces(out, indent);
                out.append(']');
            }
            case Text text -> out.append(Quoting.doubleQuoted(text.value()));
            case Quoted quoted -> out.append(Quoting.doubleQuoted(quoted.value()));
            case Plain plain -> out.append(plain.text());
        }
    }

    private static void spaces(StringBuilder out, int count) {
        out.append(" ".repeat(count));
    }
}
