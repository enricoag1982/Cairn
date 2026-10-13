package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.RuleDescriptor;

/**
 * A rule written in Java (ADR-0018, capability {@code entity-rules}): logic that a schema document cannot declare, such
 * as the check digits of an identifier. A schema names a rule by name and version; a store that has the rule's code
 * runs it at commit.
 *
 * <p>This is the base of all rules. It only describes the rule; what a rule checks depends on the kind of type it is
 * for, and each kind has its own interface that adds the check: an {@link EntityRule} checks one version of an entity.
 * A rule is an instance of one of them.
 *
 * <p>A rule must be deterministic. It gives the same result for the same input with every storage implementation and
 * when data is checked again later, so it may not use the clock, files, the network, randomness or shared mutable
 * state. Anything that is not in the version it checks, it reads through its {@link RuleContext}. Java cannot enforce
 * this, so tests should run every rule against every storage implementation. A rule is shared by all writes of a store,
 * which run one at a time, but it should not keep state between calls.
 *
 * <p>A rule is found by a store when the store is created with it, or through Java's {@link java.util.ServiceLoader},
 * which needs a public class with a public constructor without parameters, or a public static {@code provider()}
 * method.
 */
public interface Rule {

    /**
     * Describes this rule: its name, version, description, the type it is for and the problem codes it can report.
     * The descriptor of a rule does not change.
     *
     * @return the descriptor, which is also what the store's schema registry lists among its
     *         {@linkplain io.github.enricoag1982.cairn.schema.SchemaRegistry#availableRules() available rules}
     */
    RuleDescriptor descriptor();
}
