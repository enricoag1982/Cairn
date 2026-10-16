package io.github.enricoag1982.cairn.query;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The options of a traversal step of a {@link Query}, with names, for the Java builder (ADR-0023, capability
 * {@code queries}): {@link Query.Builder#traverse(Traversal)} adds the step. Start one with
 * {@link #of(String, String, String)}, which binds the entity itself (depth 0) and each entity reached from it to the
 * end, as the JSON form of queries does, and set the options you need with the withers, which return a changed copy:
 *
 * <pre>{@code
 * Query.from("Organization", "o")
 *         .traverse(Traversal.of("o", "ownedBy", "owner").minDepth(1).depth("level").maxDepth(5))
 *         .build();
 * }</pre>
 *
 * <p>The record holds what the caller wrote, and the withers may be called in any order, so it can hold a minimum depth
 * above the maximum depth; that is an invalid traversal, and {@link Query.Builder#traverse(Traversal)} throws an
 * {@link IllegalArgumentException} for it. A depth below 0 is never valid and is rejected at once.
 *
 * @param from         the name of the binding of the entity to start from
 * @param name         the name of a relationship type or the inverse name of one
 * @param binding      the name that each reached entity is bound to
 * @param minDepth     the smallest depth to bind, at least 0; 0 binds the entity itself
 * @param maxDepth     the largest depth to bind; empty to traverse to the end, as each entity is reached once
 * @param relationship the name that the relationship that reached the entity is bound to, if it is bound
 * @param depth        the name that the depth is bound to, if it is bound
 * @param along        the condition that a relationship must satisfy to be followed, if there is one; it can refer to
 *                     the binding named by {@code relationship}, which it then needs, and to literals
 */
public record Traversal(
        String from,
        String name,
        String binding,
        int minDepth,
        OptionalInt maxDepth,
        Optional<String> relationship,
        Optional<String> depth,
        Optional<Condition> along) {

    /**
     * Rejects null components and a depth below 0.
     */
    public Traversal {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(maxDepth, "maxDepth");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(along, "along");
        if (minDepth < 0) {
            throw new IllegalArgumentException("The minimum depth of a traversal is 0 or more, but it is " + minDepth
                    + ".");
        }
        if (maxDepth.isPresent() && maxDepth.getAsInt() < 0) {
            throw new IllegalArgumentException("The maximum depth of a traversal is 0 or more, but it is "
                    + maxDepth.getAsInt() + ".");
        }
    }

    /**
     * Starts a traversal that binds the bound entity itself (depth 0) and every entity reached from it, to the end,
     * without binding the relationship or the depth and without a condition. Use {@link #minDepth(int)} with 1 to leave
     * the entity itself out.
     *
     * @param from    the name of the binding of the entity to start from
     * @param name    the name of a relationship type or the inverse name of one
     * @param binding the name that each reached entity is bound to
     * @return the traversal, with a minimum depth of 0 and no maximum depth
     */
    public static Traversal of(String from, String name, String binding) {
        return new Traversal(from, name, binding, 0, OptionalInt.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /**
     * Changes the smallest depth to bind.
     *
     * @param minDepth the smallest depth, at least 0; 0 binds the entity itself
     * @return a traversal with that minimum depth
     * @throws IllegalArgumentException if {@code minDepth} is negative
     */
    public Traversal minDepth(int minDepth) {
        return new Traversal(from, name, binding, minDepth, maxDepth, relationship, depth, along);
    }

    /**
     * Changes the largest depth to bind.
     *
     * @param maxDepth the largest depth, at least 0 and, when the query is built, at least the minimum depth
     * @return a traversal with that maximum depth
     * @throws IllegalArgumentException if {@code maxDepth} is negative
     */
    public Traversal maxDepth(int maxDepth) {
        return new Traversal(from, name, binding, minDepth, OptionalInt.of(maxDepth), relationship, depth, along);
    }

    /**
     * Binds the depth of each reached entity, as an int value.
     *
     * @param binding the name that the depth is bound to
     * @return a traversal that binds the depth
     */
    public Traversal depth(String binding) {
        return new Traversal(from, name, this.binding, minDepth, maxDepth, relationship,
                Optional.of(Objects.requireNonNull(binding, "binding")), along);
    }

    /**
     * Binds the relationship that reached each entity; it is empty at depth 0.
     *
     * @param binding the name that the relationship is bound to
     * @return a traversal that binds the relationship
     */
    public Traversal relationship(String binding) {
        return new Traversal(from, name, this.binding, minDepth, maxDepth, Optional.of(Objects.requireNonNull(binding,
                "binding")), depth, along);
    }

    /**
     * Follows only the relationships that meet a condition. The condition can refer to the binding named by
     * {@link #relationship(String)}, which the traversal then needs, and to literals, and to nothing else.
     *
     * @param condition the condition that a relationship must satisfy to be followed
     * @return a traversal that follows only those relationships
     */
    public Traversal along(Condition condition) {
        return new Traversal(from, name, binding, minDepth, maxDepth, relationship, depth,
                Optional.of(Objects.requireNonNull(condition, "condition")));
    }
}
