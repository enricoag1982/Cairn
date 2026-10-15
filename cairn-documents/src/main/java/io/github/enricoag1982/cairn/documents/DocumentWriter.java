package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Quoted;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Builds the {@linkplain WrittenNode tree} of a schema document from an origin and a schema definition (capability
 * {@code schema-documents}, design D7).
 *
 * <p>The members of the document are in the order {@code cairn}, {@code origin}, {@code traits}, {@code entityTypes}
 * and {@code relationshipTypes}; types and attributes are in the order of the definition, and the properties of an
 * element in the order of {@link Keys}. Every property that has its default is left out: a boolean that is
 * {@code false}, a list that is empty, an optional that is absent and a relationship kind that is an association. The
 * required members are always written. The reader fills in the defaults, so reading what was written gives back an
 * equal definition.
 */
final class DocumentWriter {

    private DocumentWriter() {
    }

    /** Writes a document as YAML in block style. */
    static String yaml(Origin origin, SchemaDefinition definition) {
        return YamlText.render(document(origin, definition));
    }

    /** Writes a document as JSON with two-space indentation. */
    static String json(Origin origin, SchemaDefinition definition) {
        return JsonText.render(document(origin, definition));
    }

    // ------------------------------------------------------------------ the document

    static Mapping document(Origin origin, SchemaDefinition definition) {
        Mapping document = new Mapping()
                .put("cairn", new Plain("1"))
                .put("origin", new Mapping()
                        .put("name", origin.name())
                        .put("version", new Quoted(origin.version())));
        section(document, "traits", definition.traits(), TraitDefinition::name, DocumentWriter::trait);
        section(document, "entityTypes", definition.entityTypes(), EntityTypeDefinition::name, DocumentWriter::entityType);
        section(document, "relationshipTypes", definition.relationshipTypes(), RelationshipTypeDefinition::name,
                DocumentWriter::relationshipType);
        return document;
    }

    /** Adds a section of types, unless there are none. */
    private static <T> void section(
            Mapping document, String key, List<T> types, Function<T, String> name, Function<T, Mapping> writer) {
        if (!types.isEmpty()) {
            Mapping section = new Mapping();
            types.forEach(type -> section.put(name.apply(type), writer.apply(type)));
            document.put(key, section);
        }
    }

    // ------------------------------------------------------------------ types

    private static Mapping trait(TraitDefinition trait) {
        Mapping mapping = new Mapping();
        trait.description().ifPresent(description -> mapping.put("description", description));
        attributes(mapping, trait.attributes());
        strings(mapping, "aliases", trait.aliases());
        flag(mapping, "deprecated", trait.deprecated());
        return mapping;
    }

    private static Mapping entityType(EntityTypeDefinition type) {
        Mapping mapping = new Mapping();
        type.description().ifPresent(description -> mapping.put("description", description));
        flag(mapping, "abstract", type.isAbstract());
        type.supertype().ifPresent(supertype -> mapping.put("supertype", supertype));
        strings(mapping, "traits", type.traits());
        attributes(mapping, type.attributes());
        strings(mapping, "aliases", type.aliases());
        flag(mapping, "deprecated", type.deprecated());
        rules(mapping, type.rules());
        checks(mapping, type.checks());
        return mapping;
    }

    private static Mapping relationshipType(RelationshipTypeDefinition type) {
        Mapping mapping = new Mapping();
        type.description().ifPresent(description -> mapping.put("description", description));
        mapping.put("source", type.source());
        mapping.put("target", type.target());
        mapping.put("targetsPerSource", new Quoted(type.targetsPerSource().toString()));
        mapping.put("sourcesPerTarget", new Quoted(type.sourcesPerTarget().toString()));
        mapping.put("inverseName", type.inverseName());
        strings(mapping, "inverseAliases", type.inverseAliases());
        if (type.kind() != RelationshipKind.ASSOCIATION) {
            mapping.put("kind", type.kind().name().toLowerCase(Locale.ROOT));
        }
        flag(mapping, "symmetric", type.symmetric());
        type.supertype().ifPresent(supertype -> mapping.put("supertype", supertype));
        attributes(mapping, type.attributes());
        strings(mapping, "aliases", type.aliases());
        flag(mapping, "deprecated", type.deprecated());
        rules(mapping, type.rules());
        checks(mapping, type.checks());
        return mapping;
    }

    // ------------------------------------------------------------------ attributes

    private static void attributes(Mapping owner, List<AttributeDefinition> attributes) {
        if (!attributes.isEmpty()) {
            Mapping section = new Mapping();
            attributes.forEach(attribute -> section.put(attribute.name(), attribute(attribute)));
            owner.put("attributes", section);
        }
    }

    private static Mapping attribute(AttributeDefinition attribute) {
        Mapping mapping = new Mapping().put("type", attribute.valueType().name().toLowerCase(Locale.ROOT));
        flag(mapping, "required", attribute.required());
        flag(mapping, "unique", attribute.unique());
        attribute.range().ifPresent(range -> mapping.put("range", range(range)));
        strings(mapping, "aliases", attribute.aliases());
        flag(mapping, "deprecated", attribute.deprecated());
        attribute.description().ifPresent(description -> mapping.put("description", description));
        if (!attribute.examples().isEmpty()) {
            mapping.put("examples",
                    new Sequence(attribute.examples().stream().<WrittenNode>map(DocumentWriter::literal).toList()));
        }
        strings(mapping, "allowedValues", attribute.allowedValues());
        attribute.pattern().ifPresent(pattern -> mapping.put("pattern", pattern));
        attribute.minLength().ifPresent(length -> mapping.put("minLength", new Plain(Integer.toString(length))));
        attribute.maxLength().ifPresent(length -> mapping.put("maxLength", new Plain(Integer.toString(length))));
        return mapping;
    }

    /** A range: the lower bound first, as {@code atLeast} or {@code greaterThan}, then the upper bound. */
    private static Mapping range(Range range) {
        Mapping mapping = new Mapping();
        range.lower().ifPresent(bound -> mapping.put(bound.inclusive() ? "atLeast" : "greaterThan", literal(bound.value())));
        range.upper().ifPresent(bound -> mapping.put(bound.inclusive() ? "atMost" : "lessThan", literal(bound.value())));
        return mapping;
    }

    /**
     * A literal, by its value type: numbers as numbers, a decimal with its scale, booleans as {@code true} or
     * {@code false}, and strings, dates and instants as strings. A literal is written as its own type, whatever the
     * attribute's value type is; the reader gives a literal the value type of its attribute if its text fits. Payloads
     * and the JSON forms of results write values the same way, so this is their way too.
     */
    static WrittenNode literal(Value value) {
        return switch (value) {
            case Value.StringValue string -> new Text(string.value());
            case Value.BooleanValue bool -> new Plain(Boolean.toString(bool.value()));
            case Value.IntValue integer -> new Plain(Integer.toString(integer.value()));
            case Value.LongValue number -> new Plain(Long.toString(number.value()));
            case Value.DecimalValue decimal -> new Plain(decimal(decimal.value()));
            case Value.DateValue date -> new Text(date.value().toString());
            case Value.InstantValue instant -> new Text(instant.value().toString());
        };
    }

    /**
     * A decimal as plain digits, which keeps its scale: {@code 1.50} stays {@code 1.50}. A decimal with a negative
     * scale, such as {@code 1E+3}, has no plain form that keeps the scale, so it is written in scientific notation,
     * which both YAML 1.2 and JSON read as a number.
     */
    private static String decimal(BigDecimal value) {
        return value.scale() >= 0 ? value.toPlainString() : value.toString();
    }

    // ------------------------------------------------------------------ rules and lists

    private static void rules(Mapping owner, List<RuleReference> rules) {
        if (!rules.isEmpty()) {
            owner.put("rules", new Sequence(rules.stream().<WrittenNode>map(rule -> new Mapping()
                    .put("name", rule.name())
                    .put("version", new Plain(Integer.toString(rule.version())))).toList()));
        }
    }

    /** The checks of a type, as a mapping from their names to their expressions and messages, in the order of the names. */
    private static void checks(Mapping owner, List<Check> checks) {
        if (!checks.isEmpty()) {
            Mapping section = new Mapping();
            checks.stream().sorted(Comparator.comparing(Check::name)).forEach(check -> {
                Mapping mapping = new Mapping().put("expression", check.expression());
                check.message().ifPresent(message -> mapping.put("message", message));
                section.put(check.name(), mapping);
            });
            owner.put("checks", section);
        }
    }

    private static void strings(Mapping owner, String key, List<String> values) {
        if (!values.isEmpty()) {
            owner.put(key, new Sequence(values.stream().<WrittenNode>map(Text::new).toList()));
        }
    }

    private static void flag(Mapping owner, String key, boolean value) {
        if (value) {
            owner.putTrue(key);
        }
    }
}
