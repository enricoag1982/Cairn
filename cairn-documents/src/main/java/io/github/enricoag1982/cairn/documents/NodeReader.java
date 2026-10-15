package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/**
 * Reads the nodes of a YAML tree strictly and collects the problems, each with its location: the part of reading that
 * schema documents and transaction payloads share (design D2 and D5 of {@code add-schema-documents}, D1 of
 * {@code add-json-payloads}).
 *
 * <p>It reads typed properties (strings, booleans, whole numbers, lists of strings, choices and literals), mappings
 * with a fixed set of keys, whose unknown keys come with the closest known key as a hint, and mappings of names. A
 * number must be written as in JSON. The reader that uses it decides where in the tree to look, and what path a
 * problem carries: the path is a parameter of every method, because it names an element of the text that is read (a
 * type of a schema document, a write of a payload).
 *
 * <p>It reads as much as it can after a problem, so that one pass finds every problem. An instance is used once, by
 * one thread.
 */
final class NodeReader {

    private static final int SHOWN_LENGTH = 60;

    /**
     * One member of a mapping.
     *
     * @param key     the key, as written
     * @param keyNode the key's node
     * @param value   the value's node
     * @param pointer the JSON Pointer of the member
     */
    record Entry(String key, Node keyNode, Node value, String pointer) {
    }

    private final List<Problem> problems = new ArrayList<>();

    // ------------------------------------------------------------------ the text as a whole

    /** The problem of a text that is not YAML: it stops reading and is the only one reported. */
    static Problem syntax(YamlTree.Failed failed, String path) {
        return new Problem(ProblemCode.DOCUMENT_SYNTAX, path,
                "The text is not valid YAML 1.2 or JSON: " + failed.message(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(new DocumentLocation("", failed.line(), failed.column())));
    }

    /**
     * Finds out whether the text declares a format version other than 1 in the key {@code cairn}. If it does, nothing
     * else should be read: the keys of another version are not known, so the problems of this version's rules would be
     * noise.
     *
     * @param root     the root of the text
     * @param path     the path of the problem
     * @param plural   what the reader reads, in the plural, such as {@code schema documents}
     * @param singular what the text is, such as {@code document}
     */
    static Optional<Problem> unsupportedVersion(Node root, String path, String plural, String singular) {
        if (root instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals("cairn")
                        && tuple.getValueNode() instanceof ScalarNode value && Scalars.isJsonInteger(value)
                        && !Scalars.integerValue(value).equals(BigInteger.ONE)) {
                    return Optional.of(new Problem(ProblemCode.UNSUPPORTED_FORMAT_VERSION, path,
                            "This reader knows " + plural + " of format version 1, but the " + singular
                                    + " declares version " + value.getValue() + ".", Optional.of("cairn: 1"),
                            Optional.of("cairn: " + value.getValue()),
                            Optional.of("Write the " + singular + " in format version 1, or use a reader for format "
                                    + "version " + value.getValue() + "."),
                            Optional.of(location(Pointers.child("", "cairn"), key))));
                }
            }
        }
        return Optional.empty();
    }

    /** Checks that the format version is an integer; its value was checked by {@link #unsupportedVersion}. */
    void formatVersion(Entry cairn, String path) {
        if (cairn != null && !(cairn.value() instanceof ScalarNode scalar && Scalars.isJsonInteger(scalar))) {
            invalidKind(cairn.value(), cairn.pointer(), path, "value of 'cairn'", "the integer 1");
        }
    }

    // ------------------------------------------------------------------ problems

    /** Adds the problems of the YAML subset check. */
    void addAll(List<Problem> found) {
        problems.addAll(found);
    }

    /** Tells whether a problem was found. */
    boolean hasProblems() {
        return !problems.isEmpty();
    }

    /** How many problems were found so far: a reader that wants to know whether reading a part found one counts them. */
    int problemCount() {
        return problems.size();
    }

    /**
     * The problems in the order of the text. An alias makes the same node appear in two places, so its problems are
     * found twice; they are the same problem, at the same place of the text, and are reported once.
     */
    List<Problem> ordered() {
        Map<List<Object>, Problem> unique = new LinkedHashMap<>();
        for (Problem problem : problems) {
            DocumentLocation at = problem.location().orElseThrow();
            unique.putIfAbsent(List.of(problem.code(), at.line(), at.column(), problem.expected().orElse(""),
                    problem.found().orElse("")), problem);
        }
        List<Problem> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator
                .comparingInt((Problem problem) -> problem.location().orElseThrow().line())
                .thenComparingInt(problem -> problem.location().orElseThrow().column()));
        return sorted;
    }

    void add(ProblemCode code, String path, String message, String expected, String found, String hint,
            DocumentLocation at) {
        problems.add(new Problem(code, path, message, Optional.ofNullable(expected), Optional.ofNullable(found),
                Optional.ofNullable(hint), Optional.of(at)));
    }

    void invalidKind(Node node, String pointer, String path, String what, String expected) {
        String found = describe(node);
        add(ProblemCode.INVALID_VALUE, path, capitalize("the " + what) + " must be " + expected + ", but it is " + found + ".",
                expected, found, hintForKind(node, expected), location(pointer, node));
    }

    /**
     * How to fix a value of the wrong kind, if a hint helps: a number that only YAML knows is written as JSON writes it,
     * and a scalar that is no string is put in quotes where a string is expected.
     */
    private static String hintForKind(Node node, String expected) {
        if (!(node instanceof ScalarNode scalar)) {
            return null;
        }
        if (Scalars.isYamlOnlyNumber(scalar) && (expected.contains("number") || expected.contains("integer"))) {
            return Scalars.jsonForm(scalar).map(json -> "Write it as a JSON number, such as " + json + ".").orElse(null);
        }
        if (expected.equals("a string") && !Scalars.isNull(scalar)) {
            return "Put the value in quotes: \"" + scalar.getValue() + "\".";
        }
        return null;
    }

    void missing(List<String> keys, String where, String path, DocumentLocation at) {
        for (String key : keys) {
            add(ProblemCode.MISSING_KEY, path, "The key '" + key + "' is missing in " + where + ".",
                    "the key '" + key + "'", null, "Add the key '" + key + "'.", at);
        }
    }

    void duplicate(String key, Node keyNode, String member, String path, String where) {
        add(ProblemCode.DUPLICATE_KEY, path, "The key '" + key + "' is given more than once in " + where + ".",
                "each key once", key, "Remove one of the keys '" + key + "'.", location(member, keyNode));
    }

    void unknown(String key, Node keyNode, String member, String path, String where, List<String> known) {
        Optional<String> closest = ClosestKey.of(key, known);
        add(ProblemCode.UNKNOWN_KEY, path, "The key '" + key + "' is not known in " + where + ".",
                "one of " + String.join(", ", known), key,
                closest.map(name -> "Did you mean '" + name + "'?").orElse("Remove the key, or use one of the known keys."),
                location(member, keyNode));
    }

    // ------------------------------------------------------------------ values

    /** Reads a string; an absent value is empty, and a value of another kind is a problem. */
    Optional<String> string(Entry entry, String path, String what) {
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.value() instanceof ScalarNode scalar && Scalars.isString(scalar)) {
            return Optional.of(scalar.getValue());
        }
        invalidKind(entry.value(), entry.pointer(), path, what, "a string");
        return Optional.empty();
    }

    /** Reads a string that must be one of some; anything else is a problem that lists the choices. */
    Optional<String> choice(Entry entry, String path, List<String> choices) {
        if (entry.value() instanceof ScalarNode scalar && Scalars.isString(scalar) && choices.contains(scalar.getValue())) {
            return Optional.of(scalar.getValue());
        }
        String found = describe(entry.value());
        String text = entry.value() instanceof ScalarNode scalar ? scalar.getValue() : "";
        String hint = ClosestKey.of(text, choices).map(name -> "Did you mean '" + name + "'?")
                .orElse("Use one of " + String.join(", ", choices) + ".");
        add(ProblemCode.INVALID_VALUE, path, "The value of '" + entry.key() + "' must be one of "
                + String.join(", ", choices) + ", but it is " + found + ".", "one of " + String.join(", ", choices),
                found, hint, location(entry.pointer(), entry.value()));
        return Optional.empty();
    }

    boolean bool(Entry entry, String path) {
        if (entry == null) {
            return false;
        }
        if (entry.value() instanceof ScalarNode scalar && Scalars.isBoolean(scalar)) {
            return Scalars.booleanValue(scalar);
        }
        invalidKind(entry.value(), entry.pointer(), path, "value of '" + entry.key() + "'", "true or false");
        return false;
    }

    OptionalInt integer(Entry entry, String path) {
        if (entry == null) {
            return OptionalInt.empty();
        }
        if (entry.value() instanceof ScalarNode scalar) {
            Optional<Integer> value = Scalars.intValue(scalar);
            if (value.isPresent()) {
                return OptionalInt.of(value.get());
            }
        }
        invalidKind(entry.value(), entry.pointer(), path, "value of '" + entry.key() + "'", "a whole number");
        return OptionalInt.empty();
    }

    /** Reads a list of strings. */
    List<String> strings(Entry entry, String path, String what) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            invalidKind(entry.value(), entry.pointer(), path, "value of " + what, "a list of strings");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node item = sequence.getValue().get(i);
            if (item instanceof ScalarNode scalar && Scalars.isString(scalar)) {
                result.add(scalar.getValue());
            } else {
                invalidKind(item, Pointers.child(entry.pointer(), i), path, "item " + (i + 1) + " of " + what, "a string");
            }
        }
        return result;
    }

    /**
     * Reads a literal, or reports a node that is none. The literal has the value type if the scalar fits it, and the
     * scalar's own YAML type otherwise (see {@link Scalars#literal}).
     *
     * @param valueType the value type of the attribute the literal belongs to, or {@code null} if it is unknown
     */
    Optional<Value> literal(Node node, String pointer, ValueType valueType, String path, String what) {
        if (node instanceof ScalarNode scalar) {
            Optional<Value> value = Scalars.literal(scalar, valueType);
            if (value.isPresent()) {
                return value;
            }
        }
        invalidKind(node, pointer, path, what, "a number written as in JSON, true or false, or a string");
        return Optional.empty();
    }

    // ------------------------------------------------------------------ mappings

    /**
     * Reads a mapping with a fixed set of keys. Unknown and duplicate keys are problems, and so are required keys that
     * are missing.
     *
     * @return the known members in the order of the text; empty if the node is no mapping
     */
    Optional<Map<String, Entry>> properties(
            Node node, String pointer, String path, String where, List<String> known, List<String> required) {
        if (!(node instanceof MappingNode mapping)) {
            invalidKind(node, pointer, path, where.startsWith("the ") ? where.substring(4) : where, "a mapping");
            return Optional.empty();
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (NodeTuple tuple : mapping.getValue()) {
            Optional<String> key = key(tuple.getKeyNode(), pointer, path);
            if (key.isEmpty()) {
                continue;
            }
            String member = Pointers.child(pointer, key.get());
            if (!seen.add(key.get())) {
                duplicate(key.get(), tuple.getKeyNode(), member, path, where);
            } else if (!known.contains(key.get())) {
                unknown(key.get(), tuple.getKeyNode(), member, path, where, known);
            } else {
                entries.put(key.get(), new Entry(key.get(), tuple.getKeyNode(), tuple.getValueNode(), member));
            }
        }
        missing(required.stream().filter(key -> !entries.containsKey(key)).toList(), where, path, location(pointer, mapping));
        return Optional.of(entries);
    }

    /**
     * Reads a mapping whose keys are names, such as the types of a section or the attributes of a write: any key is
     * accepted, but not twice.
     *
     * @param what       what the mapping is, for problems about its keys, such as {@code 'attributes'}
     * @param expected   what the value must be if it is no mapping, such as {@code a mapping from names to definitions}
     * @param memberPath the path of the element that a key names, for problems about the key
     */
    List<Entry> named(Node node, String pointer, String path, String what, String expected,
            UnaryOperator<String> memberPath) {
        if (!(node instanceof MappingNode mapping)) {
            invalidKind(node, pointer, path, "value of " + what, expected);
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NodeTuple tuple : mapping.getValue()) {
            Optional<String> key = key(tuple.getKeyNode(), pointer, path);
            if (key.isEmpty()) {
                continue;
            }
            String member = Pointers.child(pointer, key.get());
            if (seen.add(key.get())) {
                entries.add(new Entry(key.get(), tuple.getKeyNode(), tuple.getValueNode(), member));
            } else {
                add(ProblemCode.DUPLICATE_KEY, memberPath.apply(key.get()), "The key '" + key.get() + "' is defined more than "
                        + "once under " + what + "; this is the second definition.", "each name once", key.get(),
                        "Remove the second definition, or give it another name.",
                        location(member, tuple.getKeyNode()));
            }
        }
        return entries;
    }

    /** The text of a key, or a problem if the key is no scalar. */
    Optional<String> key(Node keyNode, String pointer, String path) {
        if (keyNode instanceof ScalarNode scalar) {
            return Optional.of(scalar.getValue());
        }
        invalidKind(keyNode, pointer, path, "key", "a string");
        return Optional.empty();
    }

    // ------------------------------------------------------------------ describing nodes

    static String describe(Node node) {
        return switch (node) {
            case ScalarNode scalar -> describe(scalar);
            case MappingNode _ -> "a mapping";
            case SequenceNode _ -> "a list";
            default -> "something else";
        };
    }

    private static String describe(ScalarNode scalar) {
        String text = scalar.getValue().length() > SHOWN_LENGTH
                ? scalar.getValue().substring(0, SHOWN_LENGTH) + "..." : scalar.getValue();
        if (Scalars.isNull(scalar)) {
            return "empty (null)";
        } else if (Scalars.isYamlOnlyNumber(scalar)) {
            return "the number " + text + ", which JSON does not write like that";
        } else if (Scalars.isInteger(scalar) || Scalars.isFloat(scalar)) {
            return "the number " + text;
        } else if (Scalars.isBoolean(scalar)) {
            return "the boolean " + text;
        }
        return "the string '" + text + "'";
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static DocumentLocation location(String pointer, Node node) {
        return Marks.location(pointer, node);
    }
}
