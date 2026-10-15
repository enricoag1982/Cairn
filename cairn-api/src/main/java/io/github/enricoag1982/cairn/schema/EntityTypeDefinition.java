package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The definition of an entity type (capability {@code type-model}).
 *
 * @param name        the type name
 * @param isAbstract  {@code true} if the type has no entities of its own
 * @param supertype   the name of the entity supertype, if any
 * @param traits      the names of the traits the type includes
 * @param attributes  the attributes the type declares itself
 * @param aliases     previous names of the type
 * @param deprecated  {@code true} if the type should no longer be used
 * @param description what the type means, if described
 * @param rules       the rules that apply to the type and its subtypes
 * @param checks      the checks that apply to the type and its subtypes (capability {@code checks})
 */
public record EntityTypeDefinition(
        String name,
        boolean isAbstract,
        Optional<String> supertype,
        List<String> traits,
        List<AttributeDefinition> attributes,
        List<String> aliases,
        boolean deprecated,
        Optional<String> description,
        List<RuleReference> rules,
        List<Check> checks) {

    /**
     * Creates the definition of an entity type that declares no checks.
     *
     * @param name        the type name
     * @param isAbstract  {@code true} if the type has no entities of its own
     * @param supertype   the name of the entity supertype, if any
     * @param traits      the names of the traits the type includes
     * @param attributes  the attributes the type declares itself
     * @param aliases     previous names of the type
     * @param deprecated  {@code true} if the type should no longer be used
     * @param description what the type means, if described
     * @param rules       the rules that apply to the type and its subtypes
     */
    public EntityTypeDefinition(String name, boolean isAbstract, Optional<String> supertype, List<String> traits,
            List<AttributeDefinition> attributes, List<String> aliases, boolean deprecated,
            Optional<String> description, List<RuleReference> rules) {
        this(name, isAbstract, supertype, traits, attributes, aliases, deprecated, description, rules, List.of());
    }

    /**
     * Rejects null components and copies the lists.
     */
    public EntityTypeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(supertype, "supertype");
        Objects.requireNonNull(description, "description");
        traits = List.copyOf(traits);
        attributes = List.copyOf(attributes);
        aliases = List.copyOf(aliases);
        rules = List.copyOf(rules);
        checks = List.copyOf(checks);
    }
}
