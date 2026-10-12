package io.github.enricoag1982.sysrec.schema;

import java.util.List;
import java.util.Objects;

/**
 * The definition of a trait: attributes that entity types can include (capability {@code type-model}).
 *
 * @param name       the trait name
 * @param attributes the attributes the trait declares
 * @param aliases    previous names of the trait
 * @param deprecated {@code true} if the trait should no longer be used
 */
public record TraitDefinition(
        String name,
        List<AttributeDefinition> attributes,
        List<String> aliases,
        boolean deprecated) {

    /**
     * Rejects null components and copies the lists.
     */
    public TraitDefinition {
        Objects.requireNonNull(name, "name");
        attributes = List.copyOf(attributes);
        aliases = List.copyOf(aliases);
    }
}
