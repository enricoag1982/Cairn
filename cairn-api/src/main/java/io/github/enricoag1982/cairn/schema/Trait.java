package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Optional;

/**
 * A trait in a registered schema (capability {@code type-model}).
 *
 * @param id          the schema identifier
 * @param name        the name
 * @param aliases     previous names
 * @param deprecated  {@code true} if the trait should no longer be used
 * @param description what the trait means, if described
 * @param attributes  the attributes the trait declares
 * @param origin      the origin that registered the trait
 */
public record Trait(
        TypeId id,
        String name,
        List<String> aliases,
        boolean deprecated,
        Optional<String> description,
        List<Attribute> attributes,
        Origin origin) {

    /**
     * Copies the lists.
     */
    public Trait {
        aliases = List.copyOf(aliases);
        attributes = List.copyOf(attributes);
    }

    /**
     * Finds an attribute by name or alias, compared without regard to case.
     *
     * @param nameOrAlias the attribute's name or one of its aliases
     * @return the attribute, if the trait declares it
     */
    public Optional<Attribute> attribute(String nameOrAlias) {
        return attributes.stream().filter(attribute -> attribute.isNamed(nameOrAlias)).findFirst();
    }
}
