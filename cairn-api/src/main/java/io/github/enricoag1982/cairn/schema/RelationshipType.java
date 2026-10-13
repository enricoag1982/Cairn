package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Optional;

/**
 * A relationship type in a registered schema, with its place in the type hierarchy (capability {@code type-model}).
 *
 * @param id               the schema identifier
 * @param name             the name
 * @param aliases          previous names
 * @param source           the name of the source entity type
 * @param target           the name of the target entity type
 * @param targetsPerSource how many targets each source can have
 * @param sourcesPerTarget how many sources each target can have
 * @param inverseName      the name for navigating from target to source
 * @param inverseAliases   previous inverse names
 * @param kind             composition or association
 * @param symmetric        {@code true} if the two ends are interchangeable
 * @param deprecated       {@code true} if the type should no longer be used
 * @param description      what the type means, if described
 * @param supertypes       the names of all supertypes, nearest first
 * @param subtypes         the names of all subtypes, transitively, in alphabetical order
 * @param attributes       all attributes, including those from supertypes, each with its declaring element
 * @param rules            the rules the type names itself
 * @param effectiveRules   the rules that apply to the type: those it names itself, then those of its supertypes,
 *                         nearest supertype first, each with the type that names it
 * @param origin           the origin that registered the type
 */
public record RelationshipType(
        TypeId id,
        String name,
        List<String> aliases,
        String source,
        String target,
        Multiplicity targetsPerSource,
        Multiplicity sourcesPerTarget,
        String inverseName,
        List<String> inverseAliases,
        RelationshipKind kind,
        boolean symmetric,
        boolean deprecated,
        Optional<String> description,
        List<String> supertypes,
        List<String> subtypes,
        List<Attribute> attributes,
        List<RuleReference> rules,
        List<EffectiveRule> effectiveRules,
        Origin origin) {

    /**
     * Copies the lists.
     */
    public RelationshipType {
        aliases = List.copyOf(aliases);
        inverseAliases = List.copyOf(inverseAliases);
        supertypes = List.copyOf(supertypes);
        subtypes = List.copyOf(subtypes);
        attributes = List.copyOf(attributes);
        rules = List.copyOf(rules);
        effectiveRules = List.copyOf(effectiveRules);
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
