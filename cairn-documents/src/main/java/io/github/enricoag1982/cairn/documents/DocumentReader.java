package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.NodeReader.Entry;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
 * so that one pass finds every problem; the definition it builds is returned only if there is none. The strict reading
 * of nodes (typed properties, unknown and missing keys, wrong kinds, locations) is {@link NodeReader}'s, which payloads
 * use too; what is specific to schema documents is here: the sections, their element paths, and the definition.
 */
final class DocumentReader {

    private static final String DOCUMENT_PATH = Keys.DOCUMENT_PATH;
    private static final String ORIGIN_PATH = Keys.ORIGIN_PATH;

    private final NodeReader nodes = new NodeReader();
    private final Map<String, DocumentLocation> elements = new HashMap<>();
    private final Map<String, ExpressionPlace> expressions = new HashMap<>();
    private final String text;
    private int[] codePoints;

    private DocumentReader(String text) {
        this.text = text;
    }

    /** Reads a schema document. */
    static DocumentReadResult read(String text) {
        return switch (YamlTree.compose(text)) {
            case YamlTree.Failed failed ->
                    new DocumentReadResult.Invalid(List.of(NodeReader.syntax(failed, DOCUMENT_PATH)));
            case YamlTree.Composed composed -> new DocumentReader(text).readDocument(composed);
        };
    }

    // ------------------------------------------------------------------ the document

    private DocumentReadResult readDocument(YamlTree.Composed composed) {
        Optional<Problem> unsupported = composed.root()
                .flatMap(root -> NodeReader.unsupportedVersion(root, DOCUMENT_PATH, "schema documents", "document"));
        if (unsupported.isPresent()) {
            return new DocumentReadResult.Invalid(List.of(unsupported.get()));
        }
        DocumentLocation rootLocation =
                composed.root().map(root -> location("", root)).orElse(new DocumentLocation("", 1, 1));
        nodes.addAll(YamlSubset.problems(composed, "a schema document", DocumentReader::elementPath));
        if (composed.root().isEmpty()) {
            nodes.missing(List.of("cairn", "origin"), "the document", DOCUMENT_PATH, rootLocation);
            return invalid();
        }
        Node root = composed.root().get();
        Optional<Map<String, Entry>> top =
                nodes.properties(root, "", DOCUMENT_PATH, "the document", Keys.DOCUMENT, Keys.DOCUMENT_REQUIRED);
        if (top.isEmpty()) {
            return invalid();
        }
        nodes.formatVersion(top.get().get("cairn"), DOCUMENT_PATH);
        Origin origin = new Origin("", "");
        List<TraitDefinition> traits = new ArrayList<>();
        List<EntityTypeDefinition> entityTypes = new ArrayList<>();
        List<RelationshipTypeDefinition> relationshipTypes = new ArrayList<>();
        for (Entry entry : top.get().values()) {
            switch (entry.key()) {
                case Keys.SCHEMA -> nodes.string(entry, DOCUMENT_PATH, "value of '$schema'");
                case "origin" -> origin = origin(entry);
                case "traits" -> types(entry).forEach(type -> traits.add(trait(type)));
                case "entityTypes" -> types(entry).forEach(type -> entityTypes.add(entityType(type)));
                case "relationshipTypes" -> types(entry).forEach(type -> relationshipTypes.add(relationshipType(type)));
                default -> { }
            }
        }
        if (nodes.hasProblems()) {
            return invalid();
        }
        SchemaDefinition definition = new SchemaDefinition(entityTypes, traits, relationshipTypes);
        return new DocumentReadResult.Read(
                new SchemaDocument(origin, definition, new DocumentLocations(rootLocation, elements, expressions)));
    }

    private DocumentReadResult invalid() {
        return new DocumentReadResult.Invalid(nodes.ordered());
    }

    /** The path of the element that the node at a position in the document belongs to (design D5). */
    private static String elementPath(List<String> tokens) {
        if (tokens.isEmpty()) {
            return DOCUMENT_PATH;
        }
        String section = tokens.getFirst();
        if (section.equals("origin")) {
            return ORIGIN_PATH;
        }
        boolean types = section.equals("traits") || section.equals("entityTypes") || section.equals("relationshipTypes");
        if (!types || tokens.size() < 2) {
            return DOCUMENT_PATH;
        }
        String type = tokens.get(1);
        return tokens.size() >= 4 && (tokens.get(2).equals("attributes") || tokens.get(2).equals("checks"))
                ? type + "." + tokens.get(3) : type;
    }

    private Origin origin(Entry entry) {
        elements.putIfAbsent(DocumentLocations.key(ORIGIN_PATH), location(entry.pointer(), entry.keyNode()));
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), ORIGIN_PATH, "the origin",
                Keys.ORIGIN, Keys.ORIGIN_REQUIRED);
        if (properties.isEmpty()) {
            return new Origin("", "");
        }
        return new Origin(
                nodes.string(properties.get().get("name"), ORIGIN_PATH, "value of 'name'").orElse(""),
                nodes.string(properties.get().get("version"), ORIGIN_PATH, "value of 'version'").orElse(""));
    }

    /** The members of a section of types, in the order of the text. */
    private List<Entry> types(Entry section) {
        return nodes.named(section.value(), section.pointer(), DOCUMENT_PATH, "'" + section.key() + "'",
                "a mapping from names to definitions", UnaryOperator.identity());
    }

    // ------------------------------------------------------------------ types

    private TraitDefinition trait(Entry type) {
        String path = type.key();
        locateType(type);
        Optional<Map<String, Entry>> properties = nodes.properties(type.value(), type.pointer(), path, "trait " + path,
                Keys.TRAIT, List.of());
        if (properties.isEmpty()) {
            return new TraitDefinition(path, List.of(), List.of(), false, Optional.empty());
        }
        Map<String, Entry> p = properties.get();
        return new TraitDefinition(
                path,
                attributes(p.get("attributes"), path),
                nodes.strings(p.get("aliases"), path, "'aliases'"),
                nodes.bool(p.get("deprecated"), path),
                nodes.string(p.get("description"), path, "value of 'description'"));
    }

    private EntityTypeDefinition entityType(Entry type) {
        String path = type.key();
        locateType(type);
        Optional<Map<String, Entry>> properties = nodes.properties(type.value(), type.pointer(), path, "entity type " + path,
                Keys.ENTITY_TYPE, List.of());
        if (properties.isEmpty()) {
            return new EntityTypeDefinition(path, false, Optional.empty(), List.of(), List.of(), List.of(), false,
                    Optional.empty(), List.of(), List.of());
        }
        Map<String, Entry> p = properties.get();
        // The order matters if a path names more than one element: the first one read keeps it.
        List<AttributeDefinition> attributes = attributes(p.get("attributes"), path);
        List<RuleReference> rules = rules(p.get("rules"), path);
        return new EntityTypeDefinition(
                path,
                nodes.bool(p.get("abstract"), path),
                nodes.string(p.get("supertype"), path, "value of 'supertype'"),
                nodes.strings(p.get("traits"), path, "'traits'"),
                attributes,
                nodes.strings(p.get("aliases"), path, "'aliases'"),
                nodes.bool(p.get("deprecated"), path),
                nodes.string(p.get("description"), path, "value of 'description'"),
                rules,
                checks(p.get("checks"), path));
    }

    private RelationshipTypeDefinition relationshipType(Entry type) {
        String path = type.key();
        locateType(type);
        Optional<Map<String, Entry>> properties = nodes.properties(type.value(), type.pointer(), path,
                "relationship type " + path, Keys.RELATIONSHIP_TYPE, Keys.RELATIONSHIP_TYPE_REQUIRED);
        if (properties.isEmpty()) {
            return new RelationshipTypeDefinition(path, "", "", Multiplicity.ZERO_OR_MORE, Multiplicity.ZERO_OR_MORE,
                    "", List.of(), RelationshipKind.ASSOCIATION, false, false, Optional.empty(), List.of(), List.of(),
                    false, Optional.empty(), List.of(), List.of());
        }
        Map<String, Entry> p = properties.get();
        // The order matters if a path names more than one element: the first one read keeps it.
        List<AttributeDefinition> attributes = attributes(p.get("attributes"), path);
        String inverseName = nodes.string(p.get("inverseName"), path, "value of 'inverseName'").orElse("");
        if (!inverseName.isEmpty()) {
            Entry entry = p.get("inverseName");
            elements.putIfAbsent(DocumentLocations.key(path + "." + inverseName), location(entry.pointer(), entry.keyNode()));
        }
        List<RuleReference> rules = rules(p.get("rules"), path);
        List<Check> checks = checks(p.get("checks"), path);
        return new RelationshipTypeDefinition(
                path,
                nodes.string(p.get("source"), path, "value of 'source'").orElse(""),
                nodes.string(p.get("target"), path, "value of 'target'").orElse(""),
                multiplicity(p.get("targetsPerSource"), path),
                multiplicity(p.get("sourcesPerTarget"), path),
                inverseName,
                nodes.strings(p.get("inverseAliases"), path, "'inverseAliases'"),
                kind(p.get("kind"), path),
                nodes.bool(p.get("symmetric"), path),
                nodes.bool(p.get("outlivesTarget"), path),
                nodes.string(p.get("supertype"), path, "value of 'supertype'"),
                attributes,
                nodes.strings(p.get("aliases"), path, "'aliases'"),
                nodes.bool(p.get("deprecated"), path),
                nodes.string(p.get("description"), path, "value of 'description'"),
                rules,
                checks);
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
        List<Entry> declared = nodes.named(entry.value(), entry.pointer(), ownerPath, "'attributes'",
                "a mapping from names to definitions", key -> ownerPath + "." + key);
        for (Entry attribute : declared) {
            result.add(attribute(attribute, ownerPath + "." + attribute.key()));
        }
        return result;
    }

    private AttributeDefinition attribute(Entry attribute, String path) {
        elements.putIfAbsent(DocumentLocations.key(path), location(attribute.pointer(), attribute.keyNode()));
        Optional<Map<String, Entry>> properties = nodes.properties(attribute.value(), attribute.pointer(), path,
                "attribute " + path, Keys.ATTRIBUTE, Keys.ATTRIBUTE_REQUIRED);
        String name = attribute.key();
        if (properties.isEmpty()) {
            return new AttributeDefinition(name, ValueType.STRING, false, false, Optional.empty(), List.of(), false,
                    Optional.empty(), List.of(), List.of(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
                    Optional.empty(), Optional.empty());
        }
        Map<String, Entry> p = properties.get();
        ValueType valueType = valueType(p.get("type"), path);
        return new AttributeDefinition(
                name,
                valueType == null ? ValueType.STRING : valueType,
                nodes.bool(p.get("required"), path),
                nodes.bool(p.get("unique"), path),
                range(p.get("range"), valueType, path),
                nodes.strings(p.get("aliases"), path, "'aliases'"),
                nodes.bool(p.get("deprecated"), path),
                nodes.string(p.get("description"), path, "value of 'description'"),
                examples(p.get("examples"), valueType, path),
                nodes.strings(p.get("allowedValues"), path, "'allowedValues'"),
                nodes.string(p.get("pattern"), path, "value of 'pattern'"),
                nodes.integer(p.get("minLength"), path),
                nodes.integer(p.get("maxLength"), path),
                derived(p.get("derived"), path),
                nodes.string(p.get("classification"), path, "value of 'classification'"));
    }

    /**
     * Reads the expression of a derived attribute, and remembers where it is, so that a problem about the expression is
     * located at the key {@code derived}, and in the expression where the text allows it.
     */
    private Optional<String> derived(Entry entry, String path) {
        Optional<String> expression = nodes.string(entry, path, "value of 'derived'");
        if (entry != null && entry.value() instanceof ScalarNode scalar && Scalars.isString(scalar)) {
            expressions.putIfAbsent(DocumentLocations.derivationKey(path),
                    ExpressionPlace.of(codePoints(), entry, scalar));
        }
        return expression;
    }

    private ValueType valueType(Entry entry, String path) {
        if (entry == null) {
            return null;
        }
        Optional<String> name = nodes.choice(entry, path, Keys.VALUE_TYPES);
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
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path,
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
            nodes.add(ProblemCode.INVALID_VALUE, path, "The range of " + path + " has two " + side + " bounds, '" + inclusive.key()
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
        return nodes.literal(entry.value(), entry.pointer(), valueType, path, "value of '" + entry.key() + "'")
                .map(value -> new Bound(value, inclusive != null));
    }

    private List<Value> examples(Entry entry, ValueType valueType, String path) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            nodes.invalidKind(entry.value(), entry.pointer(), path, "value of 'examples'", "a list of values");
            return List.of();
        }
        List<Value> result = new ArrayList<>();
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node item = sequence.getValue().get(i);
            nodes.literal(item, Pointers.child(entry.pointer(), i), valueType, path, "item " + (i + 1) + " of 'examples'")
                    .ifPresent(result::add);
        }
        return result;
    }

    // ------------------------------------------------------------------ rules, multiplicities, kinds

    private List<RuleReference> rules(Entry entry, String typePath) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            nodes.invalidKind(entry.value(), entry.pointer(), typePath, "value of 'rules'", "a list of rule references");
            return List.of();
        }
        List<RuleReference> result = new ArrayList<>();
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node item = sequence.getValue().get(i);
            String pointer = Pointers.child(entry.pointer(), i);
            Optional<String> peeked = peekName(item);
            String path = peeked.map(name -> typePath + "." + name).orElse(typePath);
            peeked.ifPresent(name -> elements.putIfAbsent(DocumentLocations.key(path), location(pointer, item)));
            Optional<Map<String, Entry>> properties = nodes.properties(item, pointer, path,
                    "rule reference " + (i + 1) + " of " + typePath, Keys.RULE, Keys.RULE_REQUIRED);
            if (properties.isPresent()) {
                String name = nodes.string(properties.get().get("name"), path, "value of 'name'").orElse("");
                int version = nodes.integer(properties.get().get("version"), path).orElse(1);
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

    // ------------------------------------------------------------------ checks

    private List<Check> checks(Entry entry, String typePath) {
        if (entry == null) {
            return List.of();
        }
        List<Check> result = new ArrayList<>();
        List<Entry> declared = nodes.named(entry.value(), entry.pointer(), typePath, "'checks'",
                "a mapping from check names to checks", key -> typePath + "." + key);
        for (Entry check : declared) {
            result.add(check(check, typePath + "." + check.key()));
        }
        return result;
    }

    private Check check(Entry check, String path) {
        elements.putIfAbsent(DocumentLocations.key(path), location(check.pointer(), check.keyNode()));
        Optional<Map<String, Entry>> properties = nodes.properties(check.value(), check.pointer(), path,
                "check " + path, Keys.CHECK, Keys.CHECK_REQUIRED);
        if (properties.isEmpty()) {
            return new Check(check.key(), "", Optional.empty());
        }
        Map<String, Entry> p = properties.get();
        Entry expression = p.get("expression");
        String text = nodes.string(expression, path, "value of 'expression'").orElse("");
        if (expression != null && expression.value() instanceof ScalarNode scalar && Scalars.isString(scalar)) {
            expressions.putIfAbsent(DocumentLocations.checkKey(path), ExpressionPlace.of(codePoints(), expression, scalar));
        }
        return new Check(check.key(), text, nodes.string(p.get("message"), path, "value of 'message'"));
    }

    /** The text of the document as code points, which the marks of the nodes index; made on first use. */
    private int[] codePoints() {
        if (codePoints == null) {
            codePoints = text.codePoints().toArray();
        }
        return codePoints;
    }

    private Multiplicity multiplicity(Entry entry, String path) {
        if (entry == null) {
            return Multiplicity.ZERO_OR_MORE;
        }
        Optional<String> text = nodes.choice(entry, path, Keys.MULTIPLICITIES);
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
        Optional<String> text = nodes.choice(entry, path, Keys.KINDS);
        for (RelationshipKind kind : RelationshipKind.values()) {
            if (text.isPresent() && kind.name().toLowerCase(Locale.ROOT).equals(text.get())) {
                return kind;
            }
        }
        return RelationshipKind.ASSOCIATION;
    }

    private static DocumentLocation location(String pointer, Node node) {
        return Marks.location(pointer, node);
    }
}
