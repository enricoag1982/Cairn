package io.github.enricoag1982.cairn.schema;

import java.util.List;
import java.util.Objects;

/**
 * The description of a rule that is available to a schema registry (capability {@code type-model}).
 *
 * <p>A descriptor is plain data, so that tools, schema documents and tests can use it without loading the code of the
 * rule. A schema refers to the rule with a {@link RuleReference}; the registry checks that reference against the
 * descriptors it was given.
 *
 * @param name        the rule's name, for example {@code lei-checksum}
 * @param version     the rule's version, starting at 1
 * @param description what the rule checks
 * @param appliesTo   the name of the entity type or relationship type the rule is for; a type may name the rule if it is
 *                    this type or one of its subtypes
 * @param codes       the problem codes the rule can report, each with an explanation
 */
public record RuleDescriptor(
        String name,
        int version,
        String description,
        String appliesTo,
        List<RuleCode> codes) {

    /**
     * Rejects null components and copies the code list.
     */
    public RuleDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(appliesTo, "appliesTo");
        codes = List.copyOf(codes);
    }
}
