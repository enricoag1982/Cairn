package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.RuleDescriptor;

/**
 * A rule written in Java (ADR-0018, capability {@code entity-rules}): logic that a schema document cannot declare, such
 * as the check digits of an identifier. A schema names a rule by name and version; a store that has the rule's code
 * runs it at commit.
 *
 * <p>This is the base of all rules. It only describes the rule; what a rule checks depends on the kind of type it is
 * for, and each kind has its own interface that adds the check: an {@link EntityRule} checks one version of an entity,
 * and a {@link RelationshipRule} one version of a relationship. A rule is an instance of one of them, and it is for a
 * type of the kind that its interface checks.
 *
 * <p>A rule must be deterministic. It gives the same result for the same input with every storage implementation and
 * when data is checked again later, so it may not use the clock, files, the network, randomness or shared mutable
 * state. Anything that is not in the version it checks, it reads through its {@link RuleContext}. Java cannot enforce
 * this, so tests should run every rule against every storage implementation.
 *
 * <p>A rule runs when a transaction changes the values of what it checks, and again when a transaction changes what the
 * rule read the last time it checked a version (ADR-0025): the store records what a rule reads through its
 * {@link RuleContext}, and runs the rule again for that version, which is called a re-check. So a rule is named only by
 * the type it checks, however far from that type the data it reads is, and it has to read what its verdict depends on
 * through its context and nowhere else. See {@link RuleContext}.
 *
 * <p>A rule must be thread-safe. A store runs its rules one at a time, in the thread that commits, but stores that are
 * created with the same rule objects, for example from the same settings, call them at the same time from different
 * threads. A rule that keeps no state between calls is thread-safe. A rule must not start work on other threads and wait
 * for it if that work writes to the store: the store holds its writer lock while it runs rules, so that deadlocks.
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
