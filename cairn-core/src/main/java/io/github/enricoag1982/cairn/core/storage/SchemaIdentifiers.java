package io.github.enricoag1982.cairn.core.storage;

import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.List;
import java.util.Objects;

/**
 * The schema identifiers of every element of the schema after a registration, and the first identifier that is free after
 * it (ADR-0022, "stable identifiers"). Values are logged by attribute identifier, so recovery compiles the logged
 * definition with exactly these identifiers: it matches no names and aliases against the earlier schema and numbers
 * nothing, whatever way a later version does either.
 *
 * @param types      the identifier of each type, trait and relationship type of the schema, those that earlier
 *                   registrations added and those that this one did
 * @param attributes the identifier of each attribute of the schema, by the type or trait that declares it
 * @param nextId     the first identifier that no element has and none will be given again
 */
public record SchemaIdentifiers(List<TypeAssignment> types, List<AttributeAssignment> attributes, long nextId) {

    /**
     * The identifier of a type, trait or relationship type.
     *
     * @param name the name of the type, as the definition declares it
     * @param id   the identifier
     */
    public record TypeAssignment(String name, TypeId id) {

        /**
         * Rejects null components.
         */
        public TypeAssignment {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * The identifier of an attribute.
     *
     * @param declarer the name of the type or trait that declares the attribute, as the definition declares it
     * @param name     the name of the attribute
     * @param id       the identifier
     */
    public record AttributeAssignment(String declarer, String name, AttributeId id) {

        /**
         * Rejects null components.
         */
        public AttributeAssignment {
            Objects.requireNonNull(declarer, "declarer");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Copies the lists.
     */
    public SchemaIdentifiers {
        types = List.copyOf(types);
        attributes = List.copyOf(attributes);
    }
}
