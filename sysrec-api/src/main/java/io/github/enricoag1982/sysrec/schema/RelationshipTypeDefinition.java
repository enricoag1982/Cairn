package io.github.enricoag1982.sysrec.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The definition of a relationship type (capability {@code type-model}).
 *
 * @param name             the type name, which also names navigation from source to target
 * @param source           the name of the source entity type
 * @param target           the name of the target entity type
 * @param targetsPerSource how many targets each source can have
 * @param sourcesPerTarget how many sources each target can have
 * @param inverseName      the name of navigation from target to source
 * @param inverseAliases   previous inverse names
 * @param kind             composition or association
 * @param symmetric        {@code true} if the two ends are interchangeable
 * @param supertype        the name of the relationship supertype, if any
 * @param attributes       the attributes the type declares itself
 * @param aliases          previous names of the type
 * @param deprecated       {@code true} if the type should no longer be used
 */
public record RelationshipTypeDefinition(
        String name,
        String source,
        String target,
        Multiplicity targetsPerSource,
        Multiplicity sourcesPerTarget,
        String inverseName,
        List<String> inverseAliases,
        RelationshipKind kind,
        boolean symmetric,
        Optional<String> supertype,
        List<AttributeDefinition> attributes,
        List<String> aliases,
        boolean deprecated) {

    /**
     * Rejects null components and copies the lists.
     */
    public RelationshipTypeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetsPerSource, "targetsPerSource");
        Objects.requireNonNull(sourcesPerTarget, "sourcesPerTarget");
        Objects.requireNonNull(inverseName, "inverseName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(supertype, "supertype");
        inverseAliases = List.copyOf(inverseAliases);
        attributes = List.copyOf(attributes);
        aliases = List.copyOf(aliases);
    }
}
