package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.Schema;
import java.util.Objects;
import java.util.Optional;

/**
 * Generates JSON Schemas (draft 2020-12) from a schema (capability {@code json-payloads}), for agents and editors that
 * build valid payloads.
 *
 * <p>The text is JSON with two-space indentation, ending in a line break, and the same schema gives the same text.
 * Patterns of attributes, which are I-Regexp (RFC 9485), are published with the RFC's translation to ECMAScript, anchored
 * as {@code ^(?:...)$}: compile them with the {@code u} flag.
 */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    /**
     * Generates the JSON Schema for the values of an entity type or a relationship type, the values of a {@code create}
     * or {@code relate} write, or the {@code values} of an entity state or relationship state written as JSON.
     *
     * <p>The schema is an object with a property for each attribute of the type, the attributes it inherits from its
     * supertypes and traits included, that allows no other properties, and requires the attributes that are required.
     * A property has the value type of its attribute: a {@code string}, with {@code enum} for the allowed values, a
     * {@code pattern}, {@code minLength} and {@code maxLength}; an {@code integer} within 32 or 64 bits and the range of
     * the attribute; a {@code number} for a decimal, within its range; a {@code boolean}; a {@code string} with the
     * {@code format} {@code date} or {@code date-time}, and a {@code pattern} as well, because validators need not check
     * formats. The description and the examples of the attribute are copied. JSON Schema cannot order dates, so the
     * range of a date or instant attribute is only said in its description. A derived attribute (capability
     * {@code derived-attributes}) is listed, as {@code readOnly}, with its expression in the description: the values of a
     * state have it, but a write cannot set it, so the schema of transaction payloads leaves it out.
     *
     * @param schema   the schema that has the type
     * @param typeName the name or an alias of an entity type or relationship type, compared without regard to case
     * @return the JSON Schema, as JSON text
     * @throws IllegalArgumentException if the schema has no entity type or relationship type of that name
     */
    public static String values(Schema schema, String typeName) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(typeName, "typeName");
        Optional<EntityType> entityType = schema.entityType(typeName);
        if (entityType.isPresent()) {
            EntityType type = entityType.get();
            return JsonText.render(ValuesSchema.document("entity type", type.name(), type.description(), type.attributes()));
        }
        Optional<RelationshipType> relationshipType = schema.relationshipType(typeName);
        if (relationshipType.isPresent()) {
            RelationshipType type = relationshipType.get();
            return JsonText.render(
                    ValuesSchema.document("relationship type", type.name(), type.description(), type.attributes()));
        }
        throw new IllegalArgumentException("The schema has no entity type or relationship type '" + typeName + "'.");
    }

    /**
     * Generates the JSON Schema for transaction payloads of format version 1, in the form in which
     * {@link Payloads#readTransaction(String, Schema, IdLookup)} reads them.
     *
     * <p>The schema has the keys of the payload and of each kind of write, with the required ones, and allows no other
     * keys, besides {@code $schema}, which names a schema for editors. The values of a {@code create} or
     * {@code relate} write are checked against the values of the entity type or relationship type that it names, as
     * {@link #values(Schema, String)} describes them: a {@code oneOf} with a branch for each concrete entity type or
     * relationship type, which matches its name and aliases as the schema spells them, and requires {@code values} if
     * the type has required attributes that are not derived, and which leaves out the derived attributes, which a write
     * cannot set. The engine also accepts other capitalizations of type names and attribute names, which the schema
     * does not. The instants of the writes, {@code from} and {@code until}, are checked by
     * pattern as well as by format; one with an offset other than {@code Z} is valid, and the reader converts it to
     * UTC. The type of a {@code patch} or {@code replace} is known only to the store, so their values are checked by
     * name only, against the names and aliases of the attributes of all types that are not derived there.
     *
     * <p>Every payload that {@code Payloads.readTransaction} reads and the store commits without value problems, and that
     * spells the names of types and attributes as the schema does, is valid against the schema, with one exception: the
     * schema requires the required attributes in the values of the write that creates an entity or relationship, while a
     * store also accepts a transaction whose later write sets them.
     *
     * @param schema the schema whose types the payloads use
     * @return the JSON Schema, as JSON text
     */
    public static String transactions(Schema schema) {
        Objects.requireNonNull(schema, "schema");
        return JsonText.render(TransactionSchema.of(schema));
    }
}
