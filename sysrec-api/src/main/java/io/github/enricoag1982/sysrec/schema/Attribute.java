package io.github.enricoag1982.sysrec.schema;

import java.util.List;
import java.util.Optional;

/**
 * An attribute in a registered schema.
 *
 * @param id         the schema identifier
 * @param name       the name
 * @param aliases    previous names
 * @param valueType  the kind of value
 * @param required   {@code true} if a value is required
 * @param unique     {@code true} if values must be unique
 * @param range      the allowed values, if restricted
 * @param deprecated {@code true} if the attribute should no longer be used
 * @param declaredBy the name of the entity type, trait or relationship type that declares the attribute
 */
public record Attribute(
        AttributeId id,
        String name,
        List<String> aliases,
        ValueType valueType,
        boolean required,
        boolean unique,
        Optional<Range> range,
        boolean deprecated,
        String declaredBy) {

    /**
     * Copies the alias list.
     */
    public Attribute {
        aliases = List.copyOf(aliases);
    }

    /**
     * Tells whether this attribute has the given name or alias, compared without regard to case.
     *
     * @param nameOrAlias the name to look for
     * @return {@code true} if the name or one of the aliases matches
     */
    public boolean isNamed(String nameOrAlias) {
        return name.equalsIgnoreCase(nameOrAlias) || aliases.stream().anyMatch(nameOrAlias::equalsIgnoreCase);
    }
}
