package io.github.enricoag1982.cairn.query;

import java.util.Objects;
import java.util.Optional;

/**
 * One step of a {@link Query} after its start (ADR-0023, capability {@code queries}). Steps apply in order, each to the
 * rows that the steps before it produced.
 */
public sealed interface QueryStep {

    /**
     * Keeps the rows for which a condition holds.
     *
     * @param condition the condition
     */
    record Filter(Condition condition) implements QueryStep {

        /**
         * Rejects a null condition.
         */
        public Filter {
            Objects.requireNonNull(condition, "condition");
        }
    }

    /**
     * Binds, for each row, the entity at the other end of each relationship that leads from a bound entity by a
     * relationship name or inverse name and is effective at the view's effective time, as navigation finds them. A row
     * gives one row per relationship. An optional follow keeps a row that has no such relationship, with the bindings
     * of this step empty.
     *
     * @param from         the name of the binding of the entity to lead away from
     * @param name         the name of a relationship type or the inverse name of one
     * @param binding      the name that the entity at the other end is bound to
     * @param relationship the name that the relationship is bound to, if it is bound
     * @param optional     {@code true} to keep a row that has no relationship
     */
    record Follow(String from, String name, String binding, Optional<String> relationship, boolean optional)
            implements QueryStep {

        /**
         * Rejects null components.
         */
        public Follow {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(relationship, "relationship");
        }
    }

    /**
     * Binds, for each row, each entity reached from a bound entity by a relationship name or inverse name at depths
     * from a minimum to a maximum. Depth 0 is the bound entity itself, reached by no relationship. Each reached entity
     * is bound once per row, at the smallest depth at which it is reached.
     *
     * @param from         the name of the binding of the entity to start from
     * @param name         the name of a relationship type or the inverse name of one
     * @param binding      the name that the reached entity is bound to
     * @param relationship the name that the relationship it was reached through is bound to, if it is bound; it is empty
     *                     at depth 0
     * @param depth        the name that its depth is bound to, if it is bound
     * @param minDepth     the smallest depth to bind, at least 0
     * @param maxDepth     the largest depth to bind, at least {@code minDepth}
     */
    record Traverse(String from, String name, String binding, Optional<String> relationship, Optional<String> depth,
            int minDepth, int maxDepth) implements QueryStep {

        /**
         * Rejects null components, a minimum depth below 0 and a maximum depth below the minimum.
         */
        public Traverse {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(relationship, "relationship");
            Objects.requireNonNull(depth, "depth");
            if (minDepth < 0) {
                throw new IllegalArgumentException("The minimum depth of a traversal is 0 or more, but it is "
                        + minDepth + ".");
            }
            if (maxDepth < minDepth) {
                throw new IllegalArgumentException("The maximum depth of a traversal is at least its minimum depth "
                        + minDepth + ", but it is " + maxDepth + ".");
            }
        }
    }
}
