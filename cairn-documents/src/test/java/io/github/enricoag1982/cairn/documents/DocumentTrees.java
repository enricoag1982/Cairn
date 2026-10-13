package io.github.enricoag1982.cairn.documents;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads a document, YAML or JSON, into plain Java maps, lists and scalars with SnakeYAML Engine and the YAML 1.2 core
 * schema, without going through the reader under test; and writes such a tree as JSON.
 */
final class DocumentTrees {

    private DocumentTrees() {
    }

    /** Reads a YAML or JSON document into maps (in the order of the text), lists, strings, numbers and booleans. */
    static Object load(String text) {
        return new Load(LoadSettings.builder().setSchema(new CoreSchema()).build()).loadFromString(text);
    }

    /** The map at the end of a path of keys. */
    static Map<?, ?> mapAt(Object tree, String... path) {
        Object node = at(tree, path);
        if (node instanceof Map<?, ?> map) {
            return map;
        }
        throw new AssertionError("Not a mapping at " + String.join("/", path) + ": " + node);
    }

    /** The keys of the map at the end of a path of keys, in order. */
    static List<String> keysAt(Object tree, String... path) {
        return keysOf(mapAt(tree, path));
    }

    /** The keys of a map, in order. */
    static List<String> keysOf(Object map) {
        if (map instanceof Map<?, ?> entries) {
            return entries.keySet().stream().map(String::valueOf).toList();
        }
        throw new AssertionError("Not a mapping: " + map);
    }

    /** The node at the end of a path of keys. */
    static Object at(Object tree, String... path) {
        Object node = tree;
        for (String key : path) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(key)) {
                throw new AssertionError("No key '" + key + "' in path " + String.join("/", path) + " of " + tree);
            }
            node = map.get(key);
        }
        return node;
    }

    /** Writes a tree as compact JSON. */
    static String toJson(Object tree) {
        StringBuilder out = new StringBuilder();
        json(out, tree);
        return out.toString();
    }

    private static void json(StringBuilder out, Object node) {
        switch (node) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.append(first ? "" : ",");
                    first = false;
                    string(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    json(out, entry.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    out.append(i == 0 ? "" : ",");
                    json(out, list.get(i));
                }
                out.append(']');
            }
            case String text -> string(out, text);
            case BigDecimal decimal -> out.append(decimal.toPlainString());
            case Double number when number.isNaN() || number.isInfinite() -> string(out, number.toString());
            case Number number -> out.append(number);
            case Boolean bool -> out.append(bool);
            default -> throw new AssertionError("Not a JSON value: " + node.getClass() + " " + node);
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        out.append('"');
    }
}
