package io.github.enricoag1982.cairn.schema;

import java.util.Objects;

/**
 * A reference from an entity type or relationship type to a rule, by name and version (capability {@code type-model}).
 *
 * <p>The name must consist of lowercase ASCII letters, digits and hyphens, start with a letter and have at most 128
 * characters, and the version must be at least 1. Nothing is checked when a reference is built, apart from a missing
 * name; a {@link SchemaRegistry} reports a malformed reference as {@link ProblemCode#INVALID_RULE_NAME}, and one that
 * names no available rule as {@link ProblemCode#UNKNOWN_RULE}.
 *
 * @param name    the rule's name, for example {@code lei-checksum}
 * @param version the rule's version, starting at 1
 */
public record RuleReference(String name, int version) {

    /**
     * Rejects a null name.
     */
    public RuleReference {
        Objects.requireNonNull(name, "name");
    }
}
