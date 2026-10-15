package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.schema.ClosestName;
import io.github.enricoag1982.cairn.core.schema.Values;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checks a {@link UniqueKey} against a schema (ADR-0026, design D1 and D2 of {@code add-unique-key-references}): the
 * entity type exists, it has the attribute, the attribute is unique, and the value fits the attribute's value type. The
 * store's writes report an invalid key as a problem, and its views as an {@code IllegalArgumentException}, from the same
 * check.
 */
final class UniqueKeys {

    /**
     * A key that the schema accepts.
     *
     * @param type      the entity type the key names
     * @param attribute the unique attribute of the type, which the key names
     * @param value     the key's value, in the attribute's value type
     */
    record Valid(EntityType type, Attribute attribute, Value value) implements Checked {

        /** Tells whether an entity has the key's value, given its values by the identifiers of its attributes. */
        boolean heldBy(Segment segment) {
            Value held = segment.values().get(attribute.id());
            return held != null && Conversions.same(held, value);
        }
    }

    /**
     * A key that the schema does not accept, with what a problem says about it.
     *
     * @param message  what is wrong, in a sentence that names the key
     * @param expected what would be accepted, or null
     * @param found    what was given, or null
     * @param hint     how to correct the key, or null
     */
    record Invalid(String message, String expected, String found, String hint) implements Checked {
    }

    /** The outcome of checking a key: it is valid, or it is not. */
    sealed interface Checked permits Valid, Invalid {
    }

    private UniqueKeys() {
    }

    /**
     * Checks a key against a schema.
     *
     * @param schema     the schema
     * @param schemaName how a message calls the schema, such as {@code The schema} or {@code The schema at basis 3}
     * @param key        the key
     * @return the key as the schema reads it, or what is wrong with it
     */
    static Checked check(Schema schema, String schemaName, UniqueKey key) {
        Optional<EntityType> found = schema.entityType(key.entityType());
        if (found.isEmpty()) {
            List<ClosestName.Candidate> candidates = schema.entityTypes().stream()
                    .flatMap(type -> ClosestName.candidates(type.name(), type.aliases()).stream()).toList();
            return new Invalid(schemaName + " has no entity type '" + key.entityType() + "', so the unique key " + key
                    + " is not valid.", "the name or an alias of an entity type", key.entityType(),
                    ClosestName.ofCandidates(key.entityType(), candidates)
                            .map(closest -> "Did you mean '" + closest + "'?").orElse(null));
        }
        EntityType type = found.get();
        String unique = uniqueAttributes(type);
        String expected = unique.isEmpty() ? "an attribute of " + type.name() + " that is unique, but it has none"
                : "one of " + unique;
        Optional<Attribute> attribute = type.attribute(key.attribute());
        if (attribute.isEmpty()) {
            List<ClosestName.Candidate> candidates = type.attributes().stream()
                    .flatMap(each -> ClosestName.candidates(each.name(), each.aliases()).stream()).toList();
            return new Invalid("The entity type " + type.name() + " has no attribute '" + key.attribute()
                    + "', so the unique key " + key + " is not valid.", expected, key.attribute(),
                    ClosestName.ofCandidates(key.attribute(), candidates)
                            .map(closest -> "Did you mean '" + closest + "'?").orElse(null));
        }
        if (!attribute.get().unique()) {
            return new Invalid("The attribute " + attribute.get().name() + " of " + type.name() + " is not unique, so "
                    + "the unique key " + key + " does not name one entity.", expected, attribute.get().name(),
                    unique.isEmpty() ? "Name the entity by its ID." : null);
        }
        if (!Values.fits(key.value(), attribute.get().valueType())) {
            String wanted = Values.typeName(attribute.get().valueType());
            return new Invalid("The value of the unique key " + key + " is " + describe(key.value()) + ", which does "
                    + "not fit the attribute " + attribute.get().name() + ", of the type " + wanted + ".",
                    "a " + wanted + " value", describe(key.value()), null);
        }
        return new Valid(type, attribute.get(), Conversions.convert(key.value(), attribute.get().valueType()));
    }

    /** The names of the unique attributes of a type, in the order of its attributes, separated by commas. */
    private static String uniqueAttributes(EntityType type) {
        return type.attributes().stream().filter(Attribute::unique).map(Attribute::name)
                .collect(Collectors.joining(", "));
    }

    private static String describe(Value value) {
        return "the " + Values.typeName(value.valueType()) + " " + Values.describe(value);
    }
}
