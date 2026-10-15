package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes and reads a {@link SchemaDefinition} in format version 1, field by field and in the order of the record
 * components, so that every field reads back equal. Enumerations are written by name.
 *
 * <p>The checks of the types (design D1 of {@code add-cel-expressions}) are not part of the types' fields: they follow
 * the rest of a schema record as a section of their own, which is left out when no type declares a check, so that a
 * record without checks is the same as it was before checks existed, and one that has them is read only where bytes
 * remain.
 */
final class DefinitionCodec {

    private DefinitionCodec() {
    }

    // ------------------------------------------------------------------ writing

    static void write(Encoder out, SchemaDefinition definition) {
        out.writeAll(definition.entityTypes(), DefinitionCodec::writeEntityType);
        out.writeAll(definition.traits(), DefinitionCodec::writeTrait);
        out.writeAll(definition.relationshipTypes(), DefinitionCodec::writeRelationshipType);
    }

    private static void writeEntityType(Encoder out, EntityTypeDefinition type) {
        out.writeText(type.name());
        out.writeBoolean(type.isAbstract());
        out.writeOptionalText(type.supertype());
        out.writeAll(type.traits(), Encoder::writeText);
        out.writeAll(type.attributes(), DefinitionCodec::writeAttribute);
        out.writeAll(type.aliases(), Encoder::writeText);
        out.writeBoolean(type.deprecated());
        out.writeOptionalText(type.description());
        out.writeAll(type.rules(), DefinitionCodec::writeRule);
    }

    private static void writeTrait(Encoder out, TraitDefinition trait) {
        out.writeText(trait.name());
        out.writeAll(trait.attributes(), DefinitionCodec::writeAttribute);
        out.writeAll(trait.aliases(), Encoder::writeText);
        out.writeBoolean(trait.deprecated());
        out.writeOptionalText(trait.description());
    }

    private static void writeRelationshipType(Encoder out, RelationshipTypeDefinition type) {
        out.writeText(type.name());
        out.writeText(type.source());
        out.writeText(type.target());
        writeMultiplicity(out, type.targetsPerSource());
        writeMultiplicity(out, type.sourcesPerTarget());
        out.writeText(type.inverseName());
        out.writeAll(type.inverseAliases(), Encoder::writeText);
        out.writeText(type.kind().name());
        out.writeBoolean(type.symmetric());
        out.writeOptionalText(type.supertype());
        out.writeAll(type.attributes(), DefinitionCodec::writeAttribute);
        out.writeAll(type.aliases(), Encoder::writeText);
        out.writeBoolean(type.deprecated());
        out.writeOptionalText(type.description());
        out.writeAll(type.rules(), DefinitionCodec::writeRule);
    }

    /**
     * Writes the checks of the types that declare any: the entity types, then the relationship types, each with its name
     * and checks. Writes nothing if no type declares a check.
     */
    static void writeChecks(Encoder out, SchemaDefinition definition) {
        List<EntityTypeDefinition> entities = definition.entityTypes().stream()
                .filter(type -> !type.checks().isEmpty()).toList();
        List<RelationshipTypeDefinition> relationships = definition.relationshipTypes().stream()
                .filter(type -> !type.checks().isEmpty()).toList();
        if (entities.isEmpty() && relationships.isEmpty()) {
            return;
        }
        out.writeAll(entities, (encoder, type) -> {
            encoder.writeText(type.name());
            encoder.writeAll(type.checks(), DefinitionCodec::writeCheck);
        });
        out.writeAll(relationships, (encoder, type) -> {
            encoder.writeText(type.name());
            encoder.writeAll(type.checks(), DefinitionCodec::writeCheck);
        });
    }

    private static void writeCheck(Encoder out, Check check) {
        out.writeText(check.name());
        out.writeText(check.expression());
        out.writeOptionalText(check.message());
    }

    private static void writeMultiplicity(Encoder out, Multiplicity multiplicity) {
        out.writeInt(multiplicity.minimum());
        out.writeInt(multiplicity.maximum());
    }

    private static void writeRule(Encoder out, RuleReference rule) {
        out.writeText(rule.name());
        out.writeInt(rule.version());
    }

    private static void writeAttribute(Encoder out, AttributeDefinition attribute) {
        out.writeText(attribute.name());
        out.writeText(attribute.valueType().name());
        out.writeBoolean(attribute.required());
        out.writeBoolean(attribute.unique());
        out.writeBoolean(attribute.range().isPresent());
        attribute.range().ifPresent(range -> {
            writeOptionalBound(out, range.lower());
            writeOptionalBound(out, range.upper());
        });
        out.writeAll(attribute.aliases(), Encoder::writeText);
        out.writeBoolean(attribute.deprecated());
        out.writeOptionalText(attribute.description());
        out.writeAll(attribute.examples(), ValueCodec::write);
        out.writeAll(attribute.allowedValues(), Encoder::writeText);
        out.writeOptionalText(attribute.pattern());
        out.writeOptionalInt(attribute.minLength());
        out.writeOptionalInt(attribute.maxLength());
    }

    private static void writeOptionalBound(Encoder out, Optional<Bound> bound) {
        out.writeBoolean(bound.isPresent());
        bound.ifPresent(present -> {
            ValueCodec.write(out, present.value());
            out.writeBoolean(present.inclusive());
        });
    }

    // ------------------------------------------------------------------ reading

    static SchemaDefinition read(Decoder in) {
        return new SchemaDefinition(in.readAll(DefinitionCodec::readEntityType), in.readAll(DefinitionCodec::readTrait),
                in.readAll(DefinitionCodec::readRelationshipType));
    }

    private static EntityTypeDefinition readEntityType(Decoder in) {
        String name = in.readText();
        boolean isAbstract = in.readBoolean();
        Optional<String> supertype = in.readOptionalText();
        var traits = in.readAll(Decoder::readText);
        var attributes = in.readAll(DefinitionCodec::readAttribute);
        var aliases = in.readAll(Decoder::readText);
        boolean deprecated = in.readBoolean();
        Optional<String> description = in.readOptionalText();
        var rules = in.readAll(DefinitionCodec::readRule);
        return new EntityTypeDefinition(name, isAbstract, supertype, traits, attributes, aliases, deprecated,
                description, rules);
    }

    private static TraitDefinition readTrait(Decoder in) {
        String name = in.readText();
        var attributes = in.readAll(DefinitionCodec::readAttribute);
        var aliases = in.readAll(Decoder::readText);
        boolean deprecated = in.readBoolean();
        Optional<String> description = in.readOptionalText();
        return new TraitDefinition(name, attributes, aliases, deprecated, description);
    }

    private static RelationshipTypeDefinition readRelationshipType(Decoder in) {
        String name = in.readText();
        String source = in.readText();
        String target = in.readText();
        Multiplicity targetsPerSource = readMultiplicity(in);
        Multiplicity sourcesPerTarget = readMultiplicity(in);
        String inverseName = in.readText();
        var inverseAliases = in.readAll(Decoder::readText);
        RelationshipKind kind = enumeration(RelationshipKind.class, in.readText());
        boolean symmetric = in.readBoolean();
        Optional<String> supertype = in.readOptionalText();
        var attributes = in.readAll(DefinitionCodec::readAttribute);
        var aliases = in.readAll(Decoder::readText);
        boolean deprecated = in.readBoolean();
        Optional<String> description = in.readOptionalText();
        var rules = in.readAll(DefinitionCodec::readRule);
        return new RelationshipTypeDefinition(name, source, target, targetsPerSource, sourcesPerTarget, inverseName,
                inverseAliases, kind, symmetric, supertype, attributes, aliases, deprecated, description, rules);
    }

    /**
     * Reads what {@link #writeChecks} wrote and gives the types of a definition their checks. A record that has no bytes
     * left has no checks.
     *
     * @throws CorruptRecordException if the checks name a type that the definition does not have
     */
    static SchemaDefinition readChecks(Decoder in, SchemaDefinition definition) {
        if (in.remaining() == 0) {
            return definition;
        }
        Map<String, List<Check>> entities = readTypeChecks(in);
        Map<String, List<Check>> relationships = readTypeChecks(in);
        Set<String> known = new HashSet<>();
        List<EntityTypeDefinition> entityTypes = new ArrayList<>();
        for (EntityTypeDefinition type : definition.entityTypes()) {
            known.add("entity " + type.name());
            List<Check> checks = entities.get(type.name());
            entityTypes.add(checks == null ? type : new EntityTypeDefinition(type.name(), type.isAbstract(),
                    type.supertype(), type.traits(), type.attributes(), type.aliases(), type.deprecated(),
                    type.description(), type.rules(), checks));
        }
        List<RelationshipTypeDefinition> relationshipTypes = new ArrayList<>();
        for (RelationshipTypeDefinition type : definition.relationshipTypes()) {
            known.add("relationship " + type.name());
            List<Check> checks = relationships.get(type.name());
            relationshipTypes.add(checks == null ? type : new RelationshipTypeDefinition(type.name(), type.source(),
                    type.target(), type.targetsPerSource(), type.sourcesPerTarget(), type.inverseName(),
                    type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(), type.attributes(),
                    type.aliases(), type.deprecated(), type.description(), type.rules(), checks));
        }
        entities.keySet().forEach(name -> requireType(known, "entity " + name));
        relationships.keySet().forEach(name -> requireType(known, "relationship " + name));
        return new SchemaDefinition(entityTypes, definition.traits(), relationshipTypes);
    }

    private static void requireType(Set<String> known, String type) {
        if (!known.contains(type)) {
            throw new CorruptRecordException("The checks name the " + type + ", which the definition does not have.");
        }
    }

    private static Map<String, List<Check>> readTypeChecks(Decoder in) {
        Map<String, List<Check>> checks = new HashMap<>();
        for (Map.Entry<String, List<Check>> entry : in.readAll(decoder ->
                Map.entry(decoder.readText(), decoder.readAll(DefinitionCodec::readCheck)))) {
            checks.put(entry.getKey(), entry.getValue());
        }
        return checks;
    }

    private static Check readCheck(Decoder in) {
        return new Check(in.readText(), in.readText(), in.readOptionalText());
    }

    private static Multiplicity readMultiplicity(Decoder in) {
        return new Multiplicity(in.readInt(), in.readInt());
    }

    private static RuleReference readRule(Decoder in) {
        return new RuleReference(in.readText(), in.readInt());
    }

    private static AttributeDefinition readAttribute(Decoder in) {
        String name = in.readText();
        ValueType valueType = enumeration(ValueType.class, in.readText());
        boolean required = in.readBoolean();
        boolean unique = in.readBoolean();
        Optional<Range> range = in.readBoolean()
                ? Optional.of(new Range(readOptionalBound(in), readOptionalBound(in)))
                : Optional.empty();
        var aliases = in.readAll(Decoder::readText);
        boolean deprecated = in.readBoolean();
        Optional<String> description = in.readOptionalText();
        var examples = in.readAll(ValueCodec::read);
        var allowedValues = in.readAll(Decoder::readText);
        Optional<String> pattern = in.readOptionalText();
        var minLength = in.readOptionalInt();
        var maxLength = in.readOptionalInt();
        return new AttributeDefinition(name, valueType, required, unique, range, aliases, deprecated, description,
                examples, allowedValues, pattern, minLength, maxLength);
    }

    private static Optional<Bound> readOptionalBound(Decoder in) {
        return in.readBoolean() ? Optional.of(new Bound(ValueCodec.read(in), in.readBoolean())) : Optional.empty();
    }

    /** The constant of an enumeration with a name; a name the format version does not know is corrupt. */
    static <E extends Enum<E>> E enumeration(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new CorruptRecordException("'" + name + "' is not a " + type.getSimpleName() + ".", e);
        }
    }
}
