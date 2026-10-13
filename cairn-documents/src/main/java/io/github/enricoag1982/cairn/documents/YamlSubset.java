package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/**
 * Finds the YAML features outside the subset that schema documents use: anchors and aliases, explicit tags, merge keys,
 * and more than one document in a stream (design D5).
 *
 * <p>An alias is the same node in two places of the tree, so it needs no report of its own: there is no alias without an
 * anchor, and the anchor is reported. A node that was seen before is not looked into again, which also keeps the work
 * linear if aliases refer to aliases.
 */
final class YamlSubset {

    private static final String YAML_TAG_PREFIX = "tag:yaml.org,2002:";

    private final Map<Node, String> explicitTags;
    private final Map<Node, Node> mergeKeys;
    private final Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Problem> problems = new ArrayList<>();

    private YamlSubset(YamlTree.Composed composed) {
        this.explicitTags = composed.explicitTags();
        this.mergeKeys = composed.mergeKeys();
    }

    /** Finds the features of a composed document that are outside the subset, each with its location. */
    static List<Problem> problems(YamlTree.Composed composed) {
        YamlSubset subset = new YamlSubset(composed);
        composed.root().ifPresent(root -> subset.scan(root, List.of()));
        composed.extraDocument().ifPresent(mark -> subset.add(Keys.DOCUMENT_PATH,
                "The text has more than one YAML document, but a schema document is exactly one.",
                "one YAML document", "a second document, which starts here",
                "Remove the '---' separator and everything after the first document, or put the second document in a "
                        + "file of its own.", Marks.location("", mark)));
        return subset.problems;
    }

    private void scan(Node node, List<String> tokens) {
        if (!seen.add(node)) {
            return;
        }
        String pointer = pointer(tokens);
        String path = elementPath(tokens);
        node.getAnchor().ifPresent(anchor -> add(path,
                "Anchors and aliases are not supported; this is the anchor '&" + anchor.getValue() + "'.",
                "a value written out in full", "&" + anchor.getValue(),
                "Remove the anchor and every alias that refers to it ('*" + anchor.getValue()
                        + "'), and write the value out where it is used.", Marks.location(pointer, node)));
        String written = explicitTags.get(node);
        if (written != null) {
            String tag = written.startsWith(YAML_TAG_PREFIX) ? "!!" + written.substring(YAML_TAG_PREFIX.length()) : written;
            add(path, "Explicit tags are not supported; this value has the tag " + tag + ".", "a value without a tag",
                    tag, "Remove the tag. Put text in quotes if it would otherwise be read as a number or a boolean.",
                    Marks.location(pointer, node));
        }
        if (node instanceof MappingNode mapping) {
            Node mergeKey = mergeKeys.get(mapping);
            if (mergeKey != null) {
                add(path, "Merge keys ('<<') are not supported.", "a mapping written out in full", "<<",
                        "Remove the merge key, and write the entries out where they are used.",
                        Marks.location(Pointers.child(pointer, "<<"), mergeKey));
            }
            for (NodeTuple tuple : mapping.getValue()) {
                String key = tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : "";
                List<String> member = append(tokens, key);
                scan(tuple.getKeyNode(), member);
                scan(tuple.getValueNode(), member);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (int i = 0; i < sequence.getValue().size(); i++) {
                scan(sequence.getValue().get(i), append(tokens, String.valueOf(i)));
            }
        }
    }

    private void add(String path, String message, String expected, String found, String hint, DocumentLocation at) {
        problems.add(new Problem(ProblemCode.UNSUPPORTED_YAML_FEATURE, path, message, Optional.of(expected),
                Optional.of(found), Optional.of(hint), Optional.of(at)));
    }

    private static List<String> append(List<String> tokens, String token) {
        List<String> result = new ArrayList<>(tokens);
        result.add(token);
        return result;
    }

    private static String pointer(List<String> tokens) {
        String pointer = "";
        for (String token : tokens) {
            pointer = Pointers.child(pointer, token);
        }
        return pointer;
    }

    /** The path of the element that the node at a position in the document belongs to (design D5). */
    private static String elementPath(List<String> tokens) {
        if (tokens.isEmpty()) {
            return Keys.DOCUMENT_PATH;
        }
        String section = tokens.getFirst();
        if (section.equals("origin")) {
            return Keys.ORIGIN_PATH;
        }
        boolean types = section.equals("traits") || section.equals("entityTypes") || section.equals("relationshipTypes");
        if (!types || tokens.size() < 2) {
            return Keys.DOCUMENT_PATH;
        }
        String type = tokens.get(1);
        return tokens.size() >= 4 && tokens.get(2).equals("attributes") ? type + "." + tokens.get(3) : type;
    }
}
