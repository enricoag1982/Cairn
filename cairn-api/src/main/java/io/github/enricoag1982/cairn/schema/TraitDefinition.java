package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The definition of a trait: attributes that entity types can include (capability {@code type-model}).
 *
 * @param name        the trait name
 * @param attributes  the attributes the trait declares
 * @param aliases     previous names of the trait
 * @param deprecated  {@code true} if the trait should no longer be used
 * @param description what the trait means, if described
 */
public record TraitDefinition(
        String name,
        List<AttributeDefinition> attributes,
        List<String> aliases,
        boolean deprecated,
        Optional<String> description) {

    /**
     * Rejects null components and copies the lists.
     */
    public TraitDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        attributes = List.copyOf(attributes);
        aliases = List.copyOf(aliases);
    }
}
