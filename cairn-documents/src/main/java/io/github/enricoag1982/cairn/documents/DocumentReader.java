package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Reads the YAML node tree of a schema document into an origin and a schema definition, strictly, and reports every
 * problem with its location (capability {@code schema-documents}, design D2, D4, D5 and D6).
 *
 * <p>A reader is used once. It reads as much as it can after a problem, using defaults for what it could not read,
 * so that one pass finds every problem; the definition it builds is returned only if there is none.
 */
final class DocumentReader {

    private static final String DOCUMENT_PATH = Keys.DOCUMENT_PATH;
    private static final String ORIGIN_PATH = Keys.ORIGIN_PATH;
    private static final int SHOWN_LENGTH = 60;

    /**
     * One member of a mapping.
     *
     * @param key     the key, as written
     * @param keyNode the key's node
     * @param value   the value's node
     * @param pointer the JSON Pointer of the member
     */
    private record Entry(String key, Node keyNode, Node value, String pointer) {
    }

    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, DocumentLocation> elements = new HashMap<>();

    /** Reads a schema document. */
    static DocumentReadResult read(String text) {
        return switch (YamlTree.compose(text)) {
            case YamlTree.Failed failed -> new DocumentReadResult.Invalid(List.of(new Problem(
                    ProblemCode.DOCUMENT_SYNTAX, DOCUMENT_PATH, "The text is not valid YAML 1.2 or JSON: " + failed.message(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new DocumentLocation("", failed.line(), failed.column())))));
            case YamlTree.Composed composed -> new DocumentReader().readDocument(composed);
        };
    }

    // ------------------------------------------------------------------ the document

    private DocumentReadResult readDocument(YamlTree.Composed composed) {
        Optional<Problem> unsupported = composed.root().flatMap(DocumentReader::unsupportedVersion);
        if (unsupported.isPresent()) {
            return new DocumentReadResult.Invalid(List.of(unsupported.get()));
        }
        DocumentLocation rootLocation =
                composed.root().map(root -> location("", root)).orElse(new DocumentLocation("", 1, 1));
        problems.addAll(YamlSubset.problems(composed));
        if (composed.root().isEmpty()) {
            missing(List.of("cairn", "origin"), "the document", DOCUMENT_PATH, rootLocation);
            return invalid();
        }
        Node root = composed.root().get();
        Optional<Map<String, Entry>> top =
                properties(root, "", DOCUMENT_PATH, "the document", Keys.DOCUMENT, Keys.DOCUMENT_REQUIRED);
        if (top.isEmpty()) {
            return invalid();
        }
        formatVersion(top.get().get("cairn"));
        Origin origin = new Origin("", "");
        List<TraitDefinition> traits = new ArrayList<>();
        List<EntityTypeDefinition> entityTypes = new ArrayList<>();
        List<RelationshipTypeDefinition> relationshipTypes = new ArrayList<>();
        for (Entry entry : top.get().values()) {
            switch (entry.key()) {
                case Keys.SCHEMA -> string(entry, DOCUMENT_PATH, "value of '$schema'");
                case "origin" -> origin = origin(entry);
                case "traits" -> types(entry).forEach(type -> traits.add(trait(type)));
                case "entityTypes" -> types(entry).forEach(type -> entityTypes.add(entityType(type)));
                case "relationshipTypes" -> types(entry).forEach(type -> relationshipTypes.add(relationshipType(type)));
                default -> { }
            }
        }
        if (!problems.isEmpty()) {
            return invalid();
        }
        SchemaDefinition definition = new SchemaDefinition(entityTypes, traits, relationshipTypes);
        return new DocumentReadResult.Read(
                new SchemaDocument(origin, definition, new DocumentLocations(rootLocation, elements)));
    }

    /**
     * The problems in the order of the text. An alias makes the same node appear in two places, so its problems are
     * found twice; they are the same problem, at the same place of the text, and are reported once.
     */
    private DocumentReadResult invalid() {
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
        return new DocumentReadResult.Invalid(sorted);
    }

    /**
     * Finds out whether the document declares a format version other than 1. If it does, nothing else is read: the
     * keys of another version are not known, so the problems of this version's rules would be noise.
     */
    private static Optional<Problem> unsupportedVersion(Node root) {
        if (root instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals("cairn")
                        && tuple.getValueNode() instanceof ScalarNode value && Scalars.isJsonInteger(value)
                        && !Scalars.integerValue(value).equals(BigInteger.ONE)) {
                    return Optional.of(new Problem(ProblemCode.UNSUPPORTED_FORMAT_VERSION, DOCUMENT_PATH,
                            "This reader knows schema documents of format version 1, but the document declares version "
                                    + value.getValue() + ".", Optional.of("cairn: 1"), Optional.of("cairn: " + value.getValue()),
                            Optional.of("Write the document in format version 1, or use a reader for format version "
                                    + value.getValue() + "."),
                            Optional.of(location(Pointers.child("", "cairn"), key))));
                }
            }
        }
        return Optional.empty();
    }

    /** Checks that the format version is an integer; its value was checked by {@link #unsupportedVersion}. */
    private void formatVersion(Entry cairn) {
        if (cairn != null && !(cairn.value() instanceof ScalarNode scalar && Scalars.isJsonInteger(scalar))) {
            invalidKind(cairn.value(), cairn.pointer(), DOCUMENT_PATH, "value of 'cairn'", "the integer 1");
        }
    }

    private Origin origin(Entry entry) {
        elements.putIfAbsent(DocumentLocations.key(ORIGIN_PATH), location(entry.pointer(), entry.keyNode()));
        Optional<Map<String, Entry>> properties = properties(entry.value(), entry.pointer(), ORIGIN_PATH, "the origin",
                Keys.ORIGIN, Keys.ORIGIN_REQUIRED);
        if (properties.isEmpty()) {
            return new Origin("", "");
        }
        return new Origin(
                string(properties.get().get("name"), ORIGIN_PATH, "value of 'name'").orElse(""),
                string(properties.get().get("version"), ORIGIN_PATH, "value of 'version'").orElse(""));
    }

    /** The members of a section of types, in the order of the text. */
    private List<Entry> types(Entry section) {
        return named(section.value(), section.pointer(), DOCUMENT_PATH, "'" + section.key() + "'", UnaryOperator.identity());
    }

    // ------------------------------------------------------------------ types

    private TraitDefinition trait(Entry type) {
        String path = type.key();
        locateType(type);
        Optional<Map<String, Entry>> properties = properties(type.value(), type.pointer(), path, "trait " + path,
                Keys.TRAIT, List.of());
        if (properties.isEmpty()) {
            return new TraitDefinition(path, List.of(), List.of(), false, Optional.empty());
        }
        Map<String, Entry> p = properties.get();
        return new TraitDefinition(
                path,
                attributes(p.get("attributes"), path),
                strings(p.get("aliases"), path, "'aliases'"),
                bool(p.get("deprecated"), path),
                string(p.get("description"), path, "value of 'description'"));
    }

    private EntityTypeDefinition entityType(Entry type) {
        String path = type.key();
        locateType(type);
        Optional<Map<String, Entry>> properties = properties(type.value(), type.pointer(), path, "entity type " + path,
                Keys.ENTITY_TYPE, List.of());
        if (properties.isEmpty()) {
            return new EntityTypeDefinition(path, false, Optional.empty(), List.of(), List.of(), List.of(), false,
                    Optional.empty(), List.of());
        }
        Map<String, Entry> p = properties.get();
        return new EntityTypeDefinition(
                path,
                bool(p.get("abstract"), path),
                string(p.get("supertype"), path, "value of 'supertype'"),
                strings(p.get("traits"), path, "'traits'"),
                attributes(p.get("attributes"), path),
                strings(p.get("aliases"), path, "'aliases'"),
                bool(p.get("deprecated"), path),
                string(p.get("description"), path, "value of 'description'"),
                rules(p.get("rules"), path));
    }

    private RelationshipTypeDefinition relationshipType(Entry type) {
        String path = type.key();
        locateType(type);
        Optional<Map<String, Entry>> properties = properties(type.value(), type.pointer(), path,
                "relationship type " + path, Keys.RELATIONSHIP_TYPE, Keys.RELATIONSHIP_TYPE_REQUIRED);
        if (properties.isEmpty()) {
            return new RelationshipTypeDefinition(path, "", "", Multiplicity.ZERO_OR_MORE, Multiplicity.ZERO_OR_MORE,
                    "", List.of(), RelationshipKind.ASSOCIATION, false, Optional.empty(), List.of(), List.of(), false,
                    Optional.empty(), List.of());
        }
        Map<String, Entry> p = properties.get();
        // The order matters if a path names more than one element: the first one read keeps it.
        List<AttributeDefinition> attributes = attributes(p.get("attributes"), path);
        String inverseName = string(p.get("inverseName"), path, "value of 'inverseName'").orElse("");
        if (!inverseName.isEmpty()) {
            Entry entry = p.get("inverseName");
            elements.putIfAbsent(DocumentLocations.key(path + "." + inverseName), location(entry.pointer(), entry.keyNode()));
        }
        List<RuleReference> rules = rules(p.get("rules"), path);
        return new RelationshipTypeDefinition(
                path,
                string(p.get("source"), path, "value of 'source'").orElse(""),
                string(p.get("target"), path, "value of 'target'").orElse(""),
                multiplicity(p.get("targetsPerSource"), path),
                multiplicity(p.get("sourcesPerTarget"), path),
                inverseName,
                strings(p.get("inverseAliases"), path, "'inverseAliases'"),
                kind(p.get("kind"), path),
                bool(p.get("symmetric"), path),
                string(p.get("supertype"), path, "value of 'supertype'"),
                attributes,
                strings(p.get("aliases"), path, "'aliases'"),
                bool(p.get("deprecated"), path),
                string(p.get("description"), path, "value of 'description'"),
                rules);
    }

    private void locateType(Entry type) {
        elements.putIfAbsent(DocumentLocations.key(type.key()), location(type.pointer(), type.keyNode()));
    }

    // ------------------------------------------------------------------ attributes

    private List<AttributeDefinition> attributes(Entry entry, String ownerPath) {
        if (entry == null) {
            return List.of();
        }
        List<AttributeDefinition> result = new ArrayList<>();
        List<Entry> declared =
                named(entry.value(), entry.pointer(), ownerPath, "'attributes'", key -> ownerPath + "." + key);
        for (Entry attribute : declared) {
            result.add(attribute(attribute, ownerPath + "." + attribute.key()));
        }
        return result;
    }

    private AttributeDefinition attribute(Entry attribute, String path) {
        elements.putIfAbsent(DocumentLocations.key(path), location(attribute.pointer(), attribute.keyNode()));
        Optional<Map<String, Entry>> properties = properties(attribute.value(), attribute.pointer(), path,
                "attribute " + path, Keys.ATTRIBUTE, Keys.ATTRIBUTE_REQUIRED);
        String name = attribute.key();
        if (properties.isEmpty()) {
            return new AttributeDefinition(name, ValueType.STRING, false, false, Optional.empty(), List.of(), false,
                    Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty());
        }
        Map<String, Entry> p = properties.get();
        ValueType valueType = valueType(p.get("type"), path);
        return new AttributeDefinition(
                name,
                valueType == null ? ValueType.STRING : valueType,
                bool(p.get("required"), path),
                bool(p.get("unique"), path),
                range(p.get("range"), valueType, path),
                strings(p.get("aliases"), path, "'aliases'"),
                bool(p.get("deprecated"), path),
                string(p.get("description"), path, "value of 'description'"),
                examples(p.get("examples"), valueType, path),
                strings(p.get("allowedValues"), path, "'allowedValues'"),
                string(p.get("pattern"), path, "value of 'pattern'"),
                integer(p.get("minLength"), path),
                integer(p.get("maxLength"), path));
    }

    private ValueType valueType(Entry entry, String path) {
        if (entry == null) {
            return null;
        }
        Optional<String> name = choice(entry, path, Keys.VALUE_TYPES);
        for (ValueType type : ValueType.values()) {
            if (name.isPresent() && type.name().toLowerCase(Locale.ROOT).equals(name.get())) {
                return type;
            }
        }
        return null;
    }

    private Optional<Range> range(Entry entry, ValueType valueType, String path) {
        if (entry == null) {
            return Optional.empty();
        }
        Optional<Map<String, Entry>> properties = properties(entry.value(), entry.pointer(), path,
                "the range of " + path, Keys.RANGE, List.of());
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Bound> lower = bound(p.get("atLeast"), p.get("greaterThan"), entry, valueType, path, "lower");
        Optional<Bound> upper = bound(p.get("atMost"), p.get("lessThan"), entry, valueType, path, "upper");
        return Optional.of(new Range(lower, upper));
    }

    /** Reads one side of a range: the inclusive form, or the exclusive form, but not both. */
    private Optional<Bound> bound(
            Entry inclusive, Entry exclusive, Entry range, ValueType valueType, String path, String side) {
        if (inclusive != null && exclusive != null) {
            add(ProblemCode.INVALID_VALUE, path, "The range of " + path + " has two " + side + " bounds, '" + inclusive.key()
                    + "' and '" + exclusive.key() + "'; a range has at most one on each side.",
                    "at most one of '" + (side.equals("lower") ? "atLeast" : "atMost") + "' and '"
                            + (side.equals("lower") ? "greaterThan" : "lessThan") + "'",
                    "both", "Keep the bound you mean, and remove the other.", location(range.pointer(), range.value()));
            return Optional.empty();
        }
        Entry entry = inclusive != null ? inclusive : exclusive;
        if (entry == null) {
            return Optional.empty();
        }
        return literal(entry.value(), entry.pointer(), valueType, path, "value of '" + entry.key() + "'")
                .map(value -> new Bound(value, inclusive != null));
    }

    private List<Value> examples(Entry entry, ValueType valueType, String path) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            invalidKind(entry.value(), entry.pointer(), path, "value of 'examples'", "a list of values");
            return List.of();
        }
        List<Value> result = new ArrayList<>();
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node item = sequence.getValue().get(i);
            literal(item, Pointers.child(entry.pointer(), i), valueType, path, "item " + (i + 1) + " of 'examples'")
                    .ifPresent(result::add);
        }
        return result;
    }

    /** Reads a literal, or reports a node that is none. */
    private Optional<Value> literal(Node node, String pointer, ValueType valueType, String path, String what) {
        if (node instanceof ScalarNode scalar) {
            Optional<Value> value = Scalars.literal(scalar, valueType);
            if (value.isPresent()) {
                return value;
            }
        }
        invalidKind(node, pointer, path, what, "a number written as in JSON, true or false, or a string");
        return Optional.empty();
    }

    // ------------------------------------------------------------------ rules, multiplicities, kinds

    private List<RuleReference> rules(Entry entry, String typePath) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            invalidKind(entry.value(), entry.pointer(), typePath, "value of 'rules'", "a list of rule references");
            return List.of();
        }
        List<RuleReference> result = new ArrayList<>();
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node item = sequence.getValue().get(i);
            String pointer = Pointers.child(entry.pointer(), i);
            Optional<String> peeked = peekName(item);
            String path = peeked.map(name -> typePath + "." + name).orElse(typePath);
            peeked.ifPresent(name -> elements.putIfAbsent(DocumentLocations.key(path), location(pointer, item)));
            Optional<Map<String, Entry>> properties = properties(item, pointer, path,
                    "rule reference " + (i + 1) + " of " + typePath, Keys.RULE, Keys.RULE_REQUIRED);
            if (properties.isPresent()) {
                String name = string(properties.get().get("name"), path, "value of 'name'").orElse("");
                int version = integer(properties.get().get("version"), path).orElse(1);
                result.add(new RuleReference(name, version));
            }
        }
        return result;
    }

    /** The name of a rule reference, if it can be read before the reference is. */
    private static Optional<String> peekName(Node item) {
        if (item instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals("name")
                        && tuple.getValueNode() instanceof ScalarNode value && Scalars.isString(value)) {
                    return Optional.of(value.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private Multiplicity multiplicity(Entry entry, String path) {
        if (entry == null) {
            return Multiplicity.ZERO_OR_MORE;
        }
        Optional<String> text = choice(entry, path, Keys.MULTIPLICITIES);
        for (Multiplicity multiplicity : List.of(Multiplicity.ZERO_OR_ONE, Multiplicity.EXACTLY_ONE,
                Multiplicity.ZERO_OR_MORE, Multiplicity.ONE_OR_MORE)) {
            if (text.isPresent() && multiplicity.toString().equals(text.get())) {
                return multiplicity;
            }
        }
        return Multiplicity.ZERO_OR_MORE;
    }

    private RelationshipKind kind(Entry entry, String path) {
        if (entry == null) {
            return RelationshipKind.ASSOCIATION;
        }
        Optional<String> text = choice(entry, path, Keys.KINDS);
        for (RelationshipKind kind : RelationshipKind.values()) {
            if (text.isPresent() && kind.name().toLowerCase(Locale.ROOT).equals(text.get())) {
                return kind;
            }
        }
        return RelationshipKind.ASSOCIATION;
    }

    // ------------------------------------------------------------------ values

    /** Reads a string; an absent value is empty, and a value of another kind is a problem. */
    private Optional<String> string(Entry entry, String path, String what) {
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
    private Optional<String> choice(Entry entry, String path, List<String> choices) {
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

    private boolean bool(Entry entry, String path) {
        if (entry == null) {
            return false;
        }
        if (entry.value() instanceof ScalarNode scalar && Scalars.isBoolean(scalar)) {
            return Scalars.booleanValue(scalar);
        }
        invalidKind(entry.value(), entry.pointer(), path, "value of '" + entry.key() + "'", "true or false");
        return false;
    }

    private OptionalInt integer(Entry entry, String path) {
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
    private List<String> strings(Entry entry, String path, String what) {
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

    // ------------------------------------------------------------------ mappings

    /**
     * Reads a mapping with a fixed set of keys. Unknown and duplicate keys are problems, and so are required keys that
     * are missing.
     *
     * @return the known members in the order of the text; empty if the node is no mapping
     */
    private Optional<Map<String, Entry>> properties(
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
     * Reads a mapping whose keys are names, such as the types of a section: any key is accepted, but not twice.
     *
     * @param memberPath the path of the element that a key names, for problems about the key
     */
    private List<Entry> named(Node node, String pointer, String path, String what, UnaryOperator<String> memberPath) {
        if (!(node instanceof MappingNode mapping)) {
            invalidKind(node, pointer, path, "value of " + what, "a mapping from names to definitions");
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

    private Optional<String> key(Node keyNode, String pointer, String path) {
        if (keyNode instanceof ScalarNode scalar) {
            return Optional.of(scalar.getValue());
        }
        invalidKind(keyNode, pointer, path, "key", "a string");
        return Optional.empty();
    }

    private void duplicate(String key, Node keyNode, String member, String path, String where) {
        add(ProblemCode.DUPLICATE_KEY, path, "The key '" + key + "' is given more than once in " + where + ".",
                "each key once", key, "Remove one of the keys '" + key + "'.", location(member, keyNode));
    }

    private void unknown(String key, Node keyNode, String member, String path, String where, List<String> known) {
        Optional<String> closest = ClosestKey.of(key, known);
        add(ProblemCode.UNKNOWN_KEY, path, "The key '" + key + "' is not known in " + where + ".",
                "one of " + String.join(", ", known), key,
                closest.map(name -> "Did you mean '" + name + "'?").orElse("Remove the key, or use one of the known keys."),
                location(member, keyNode));
    }

    private void missing(List<String> keys, String where, String path, DocumentLocation at) {
        for (String key : keys) {
            add(ProblemCode.MISSING_KEY, path, "The key '" + key + "' is missing in " + where + ".",
                    "the key '" + key + "'", null, "Add the key '" + key + "'.", at);
        }
    }

    // ------------------------------------------------------------------ problems

    private void invalidKind(Node node, String pointer, String path, String what, String expected) {
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

    private void add(ProblemCode code, String path, String message, String expected, String found, String hint,
            DocumentLocation at) {
        problems.add(new Problem(code, path, message, Optional.ofNullable(expected), Optional.ofNullable(found),
                Optional.ofNullable(hint), Optional.of(at)));
    }

    private static String describe(Node node) {
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

    private static DocumentLocation location(String pointer, Node node) {
        return Marks.location(pointer, node);
    }
}
