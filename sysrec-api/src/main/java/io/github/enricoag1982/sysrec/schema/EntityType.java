package io.github.enricoag1982.sysrec.schema;

import java.util.List;
import java.util.Optional;

/**
 * An entity type in a registered schema, with its place in the type hierarchy (capability {@code type-model}).
 *
 * @param id         the schema identifier
 * @param name       the name
 * @param aliases    previous names
 * @param isAbstract {@code true} if the type has no entities of its own
 * @param deprecated {@code true} if the type should no longer be used
 * @param supertypes the names of all supertypes, nearest first
 * @param subtypes   the names of all subtypes, transitively, in alphabetical order
 * @param traits     the names of the traits the type includes itself
 * @param attributes all attributes, including those from supertypes and traits, each with its declaring element
 * @param origin     the origin that registered the type
 */
public record EntityType(
        TypeId id,
        String name,
        List<String> aliases,
        boolean isAbstract,
        boolean deprecated,
        List<String> supertypes,
        List<String> subtypes,
        List<String> traits,
        List<Attribute> attributes,
        Origin origin) {

    /**
     * Copies the lists.
     */
    public EntityType {
        aliases = List.copyOf(aliases);
        supertypes = List.copyOf(supertypes);
        subtypes = List.copyOf(subtypes);
        traits = List.copyOf(traits);
        attributes = List.copyOf(attributes);
    }

    /**
     * Finds an attribute by name or alias, compared without regard to case.
     *
     * @param nameOrAlias the attribute's name or one of its aliases
     * @return the attribute, if the type has it
     */
    public Optional<Attribute> attribute(String nameOrAlias) {
        return attributes.stream().filter(attribute -> attribute.isNamed(nameOrAlias)).findFirst();
    }
}
