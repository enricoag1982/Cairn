package io.github.enricoag1982.sysrec.schema;

import java.util.List;
import java.util.Optional;

/**
 * A registered, valid schema: the types of every origin, with identifiers and the resolved type hierarchy
 * (capabilities {@code type-model} and {@code schema-evolution}).
 * Instances are immutable. Lookups by name also accept aliases and ignore case.
 */
public interface Schema {

    /**
     * Lists the entity types.
     *
     * @return the entity types, in alphabetical order
     */
    List<EntityType> entityTypes();

    /**
     * Lists the traits.
     *
     * @return the traits, in alphabetical order
     */
    List<Trait> traits();

    /**
     * Lists the relationship types.
     *
     * @return the relationship types, in alphabetical order
     */
    List<RelationshipType> relationshipTypes();

    /**
     * Lists the origins whose types the schema contains, each with the version that was last applied.
     *
     * @return the origins, in alphabetical order of their names
     */
    List<Origin> origins();

    /**
     * Finds an entity type by name or alias.
     *
     * @param nameOrAlias the type's name or one of its aliases
     * @return the entity type, if any
     */
    Optional<EntityType> entityType(String nameOrAlias);

    /**
     * Finds a trait by name or alias.
     *
     * @param nameOrAlias the trait's name or one of its aliases
     * @return the trait, if any
     */
    Optional<Trait> trait(String nameOrAlias);

    /**
     * Finds a relationship type by name or alias.
     *
     * @param nameOrAlias the type's name or one of its aliases
     * @return the relationship type, if any
     */
    Optional<RelationshipType> relationshipType(String nameOrAlias);

    /**
     * Finds a relationship type by its inverse name or one of its inverse aliases.
     *
     * @param inverseNameOrAlias the inverse name or alias
     * @return the relationship type, if any
     */
    Optional<RelationshipType> relationshipTypeByInverseName(String inverseNameOrAlias);

    /**
     * Tells whether the schema has no types at all.
     *
     * @return {@code true} for the schema before the first registration
     */
    boolean isEmpty();
}
