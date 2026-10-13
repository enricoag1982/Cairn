package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Optional;

/**
 * Writes and reads a {@link SchemaDefinition} in format version 1, field by field and in the order of the record
 * components, so that every field reads back equal. Enumerations are written by name.
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
