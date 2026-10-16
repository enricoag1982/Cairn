package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.data.EntityId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A query: data that a {@linkplain io.github.enricoag1982.cairn.data.View view} runs to filter entities, follow and
 * traverse relationships, group and aggregate, and return rows (ADR-0023, capability {@code queries}).
 *
 * <p>A query starts from an entity type, binding each entity of the type (and of its subtypes, unless it asks for the
 * exact type) that is effective at the view's effective time to a name; or, if it gives a list of IDs with
 * {@link Builder#ids(EntityId...)}, only the entities with those IDs that are of the type and effective. Its steps then
 * apply in order, each to the rows that the steps before it produced: a filter keeps rows, a follow step binds the
 * entities and relationships linked by a relationship name or inverse name, and a traversal binds those reached up to a
 * maximum depth, or to the end. Then come the optional grouping with aggregates, the columns to return, an order and a
 * limit.
 *
 * <p>A query is an immutable value that can be logged, compared, sent, and run again at other coordinates. Build one
 * with {@link #from(String, String)}. For example, the total balance of accounts by ultimate parent, which follows
 * {@code ownedBy} only while the share is above 50%:
 *
 * <pre>{@code
 * Query query = Query.from("Account", "a")
 *         .follow("a", "HeldBy", "holder")
 *         .traverse("holder", "ownedBy", 0, 10, "party", "ownership",
 *                 Condition.gt(Operand.attribute("ownership", "share"), Operand.literal(50)))
 *         .where(Condition.not(Condition.hasLink("party", "ownedBy", "owns",
 *                 Condition.gt(Operand.attribute("owns", "share"), Operand.literal(50)))))
 *         .groupBy("party")
 *         .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"))
 *         .build();
 * }</pre>
 *
 * <p>The record only holds what the caller wrote. It does not know the schema: the view that runs the query checks every
 * name against its schema and the bindings, and returns problems, not an exception, for a query that does not fit.
 *
 * @param start    where the query starts
 * @param steps    the steps after the start, applied in order
 * @param grouping how the rows are grouped, if they are
 * @param columns  the columns to return, in order; empty for the default: the bindings of a query without grouping, the
 *                 keys and aggregates of a grouped query
 * @param order    how to order the rows, most important first, before the IDs of the bindings
 * @param limit    the largest number of rows to return, if limited
 */
public record Query(
        Start start,
        List<QueryStep> steps,
        Optional<Grouping> grouping,
        List<Column> columns,
        List<Order> order,
        OptionalInt limit) {

    /**
     * Rejects null components and a negative limit, and copies the lists.
     */
    public Query {
        Objects.requireNonNull(start, "start");
        steps = List.copyOf(steps);
        Objects.requireNonNull(grouping, "grouping");
        columns = List.copyOf(columns);
        order = List.copyOf(order);
        Objects.requireNonNull(limit, "limit");
        if (limit.isPresent() && limit.getAsInt() < 0) {
            throw new IllegalArgumentException("The limit of a query is 0 or more, but it is " + limit.getAsInt() + ".");
        }
    }

    /**
     * Starts building a query.
     *
     * @param entityType the name or an alias of the entity type to start from, compared without regard to case
     * @param binding    the name that each entity is bound to
     * @return the builder
     */
    public static Builder from(String entityType, String binding) {
        return new Builder(entityType, binding);
    }

    /**
     * Builds a {@link Query}. A builder is not safe for use by several threads. Every method returns the builder.
     */
    public static final class Builder {

        private final String entityType;
        private final String binding;
        private boolean exactType;
        private Optional<List<EntityId>> ids = Optional.empty();
        private final List<QueryStep> steps = new ArrayList<>();
        private boolean grouped;
        private final List<GroupKey> keys = new ArrayList<>();
        private final List<Aggregate> aggregates = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private final List<Order> order = new ArrayList<>();
        private OptionalInt limit = OptionalInt.empty();

        private Builder(String entityType, String binding) {
            this.entityType = Objects.requireNonNull(entityType, "entityType");
            this.binding = Objects.requireNonNull(binding, "binding");
        }

        /**
         * Starts from the entities whose concrete type is the entity type, without those of its subtypes.
         *
         * @return this builder
         */
        public Builder exactType() {
            this.exactType = true;
            return this;
        }

        /**
         * Starts from the entities with these IDs, instead of from every entity of the type: the ones that are of the
         * type (or of its subtypes, unless {@link #exactType()}) and effective at the view's effective time. The others
         * are left out without a problem. Replaces any IDs given before. In a rule's view, the query then reads these
         * entities and not the type.
         *
         * <p>No IDs at all means no entities: a query started from {@code ids()}, or from the IDs of an empty
         * collection, returns no rows, so that IDs computed from data that finds none do not give every entity of the
         * type. A query that never calls {@code ids} starts from every entity of the type.
         *
         * @param ids the IDs of the entities
         * @return this builder
         */
        public Builder ids(EntityId... ids) {
            return ids(List.of(Objects.requireNonNull(ids, "ids")));
        }

        /**
         * Starts from the entities with these IDs, like {@link #ids(EntityId...)}.
         *
         * <p>An empty collection means no entities, not every entity of the type.
         *
         * @param ids the IDs of the entities
         * @return this builder
         */
        public Builder ids(Collection<EntityId> ids) {
            this.ids = Optional.of(List.copyOf(Objects.requireNonNull(ids, "ids")));
            return this;
        }

        /**
         * Adds a filter step.
         *
         * @param condition the condition that a row must satisfy to be kept
         * @return this builder
         */
        public Builder where(Condition condition) {
            return step(new QueryStep.Filter(condition));
        }

        /**
         * Adds a step of any kind.
         *
         * @param step the step
         * @return this builder
         */
        public Builder step(QueryStep step) {
            steps.add(Objects.requireNonNull(step, "step"));
            return this;
        }

        /**
         * Adds a step that binds the entity at the other end of each relationship that leads from a bound entity.
         *
         * @param from    the name of the binding of the entity to lead away from
         * @param name    the name of a relationship type or the inverse name of one
         * @param binding the name that the entity at the other end is bound to
         * @return this builder
         */
        public Builder follow(String from, String name, String binding) {
            return step(new QueryStep.Follow(from, name, binding, Optional.empty(), false));
        }

        /**
         * Adds a step that binds the entity at the other end of each relationship that leads from a bound entity, and
         * the relationship.
         *
         * @param from         the name of the binding of the entity to lead away from
         * @param name         the name of a relationship type or the inverse name of one
         * @param binding      the name that the entity at the other end is bound to
         * @param relationship the name that the relationship is bound to
         * @return this builder
         */
        public Builder follow(String from, String name, String binding, String relationship) {
            return step(new QueryStep.Follow(from, name, binding, Optional.of(Objects.requireNonNull(relationship,
                    "relationship")), false));
        }

        /**
         * Adds a follow step that keeps a row without a relationship, with its new bindings empty.
         *
         * @param from    the name of the binding of the entity to lead away from
         * @param name    the name of a relationship type or the inverse name of one
         * @param binding the name that the entity at the other end is bound to
         * @return this builder
         */
        public Builder optionalFollow(String from, String name, String binding) {
            return step(new QueryStep.Follow(from, name, binding, Optional.empty(), true));
        }

        /**
         * Adds a follow step that binds the relationship too, and keeps a row without a relationship, with its new
         * bindings empty.
         *
         * @param from         the name of the binding of the entity to lead away from
         * @param name         the name of a relationship type or the inverse name of one
         * @param binding      the name that the entity at the other end is bound to
         * @param relationship the name that the relationship is bound to
         * @return this builder
         */
        public Builder optionalFollow(String from, String name, String binding, String relationship) {
            return step(new QueryStep.Follow(from, name, binding, Optional.of(Objects.requireNonNull(relationship,
                    "relationship")), true));
        }

        /**
         * Adds a traversal step that binds each entity reached from a bound entity, at its smallest depth.
         *
         * @param from     the name of the binding of the entity to start from
         * @param name     the name of a relationship type or the inverse name of one
         * @param minDepth the smallest depth to bind; 0 binds the entity itself
         * @param maxDepth the largest depth to bind
         * @param binding  the name that each reached entity is bound to
         * @return this builder
         * @throws IllegalArgumentException if {@code minDepth} is negative or {@code maxDepth} is below it
         */
        public Builder traverse(String from, String name, int minDepth, int maxDepth, String binding) {
            return traverse(from, name, minDepth, maxDepth, binding, Optional.empty(), Optional.empty());
        }

        /**
         * Adds a traversal step that binds each entity reached from a bound entity, at its smallest depth, and
         * optionally the relationship it was reached through and its depth.
         *
         * @param from         the name of the binding of the entity to start from
         * @param name         the name of a relationship type or the inverse name of one
         * @param minDepth     the smallest depth to bind; 0 binds the entity itself
         * @param maxDepth     the largest depth to bind
         * @param binding      the name that each reached entity is bound to
         * @param relationship the name that the relationship is bound to, if it is bound
         * @param depth        the name that the depth is bound to, if it is bound
         * @return this builder
         * @throws IllegalArgumentException if {@code minDepth} is negative or {@code maxDepth} is below it
         */
        public Builder traverse(String from, String name, int minDepth, int maxDepth, String binding,
                Optional<String> relationship, Optional<String> depth) {
            return traverse(from, name, minDepth, maxDepth, binding, relationship, depth, Optional.empty());
        }

        /**
         * Adds a traversal step that follows only the relationships that meet a condition, and binds the relationship
         * that reached each entity, so that the condition can refer to it.
         *
         * @param from         the name of the binding of the entity to start from
         * @param name         the name of a relationship type or the inverse name of one
         * @param minDepth     the smallest depth to bind; 0 binds the entity itself
         * @param maxDepth     the largest depth to bind
         * @param binding      the name that each reached entity is bound to
         * @param relationship the name that the relationship is bound to; {@code along} can refer to it
         * @param along        the condition that a relationship must satisfy to be followed; it can refer to the
         *                     binding named by {@code relationship} and to literals, and to nothing else
         * @return this builder
         * @throws IllegalArgumentException if {@code minDepth} is negative or {@code maxDepth} is below it
         */
        public Builder traverse(String from, String name, int minDepth, int maxDepth, String binding,
                String relationship, Condition along) {
            return traverse(from, name, minDepth, maxDepth, binding,
                    Optional.of(Objects.requireNonNull(relationship, "relationship")), Optional.empty(),
                    Optional.of(Objects.requireNonNull(along, "along")));
        }

        /**
         * Adds a traversal step that binds each entity reached from a bound entity, at its smallest depth, and
         * optionally the relationship it was reached through, its depth, and a condition that decides which
         * relationships it follows.
         *
         * @param from         the name of the binding of the entity to start from
         * @param name         the name of a relationship type or the inverse name of one
         * @param minDepth     the smallest depth to bind; 0 binds the entity itself
         * @param maxDepth     the largest depth to bind
         * @param binding      the name that each reached entity is bound to
         * @param relationship the name that the relationship is bound to, if it is bound
         * @param depth        the name that the depth is bound to, if it is bound
         * @param along        the condition that a relationship must satisfy to be followed, if there is one; it can
         *                     refer to the binding named by {@code relationship}, which it then needs, and to literals
         * @return this builder
         * @throws IllegalArgumentException if {@code minDepth} is negative or {@code maxDepth} is below it
         */
        public Builder traverse(String from, String name, int minDepth, int maxDepth, String binding,
                Optional<String> relationship, Optional<String> depth, Optional<Condition> along) {
            return step(new QueryStep.Traverse(from, name, binding, relationship, depth, along, minDepth, maxDepth));
        }

        /**
         * Adds a traversal step with named options, which can leave out the maximum depth to traverse to the end.
         *
         * @param traversal the traversal
         * @return this builder
         * @throws IllegalArgumentException if the minimum depth of the traversal is above its maximum depth
         */
        public Builder traverse(Traversal traversal) {
            Objects.requireNonNull(traversal, "traversal");
            return step(new QueryStep.Traverse(traversal.from(), traversal.name(), traversal.binding(),
                    traversal.relationship(), traversal.depth(), traversal.along(), traversal.minDepth(),
                    traversal.maxDepth()));
        }

        /**
         * Groups the rows by keys. May be called more than once; the keys add up.
         *
         * @param keys the keys
         * @return this builder
         */
        public Builder groupBy(GroupKey... keys) {
            grouped = true;
            for (GroupKey key : keys) {
                this.keys.add(Objects.requireNonNull(key, "key"));
            }
            return this;
        }

        /**
         * Groups the rows by a binding, whose column has the binding's name.
         *
         * @param binding the name of the binding
         * @return this builder
         */
        public Builder groupBy(String binding) {
            return groupBy(GroupKey.binding(binding));
        }

        /**
         * Computes aggregates for each group; without {@link #groupBy}, all rows form one group. May be called more
         * than once; the aggregates add up.
         *
         * @param aggregates the aggregates
         * @return this builder
         */
        public Builder aggregate(Aggregate... aggregates) {
            grouped = true;
            for (Aggregate aggregate : aggregates) {
                this.aggregates.add(Objects.requireNonNull(aggregate, "aggregate"));
            }
            return this;
        }

        /**
         * Names the columns to return. May be called more than once; the columns add up.
         *
         * @param columns the columns
         * @return this builder
         */
        public Builder select(Column... columns) {
            for (Column column : columns) {
                this.columns.add(Objects.requireNonNull(column, "column"));
            }
            return this;
        }

        /**
         * Orders the rows. May be called more than once; the orders add up, the first being the most important.
         *
         * @param orders the orders
         * @return this builder
         */
        public Builder orderBy(Order... orders) {
            for (Order next : orders) {
                this.order.add(Objects.requireNonNull(next, "order"));
            }
            return this;
        }

        /**
         * Limits the number of rows returned.
         *
         * @param limit the largest number of rows, 0 or more
         * @return this builder
         * @throws IllegalArgumentException if the limit is negative
         */
        public Builder limit(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("The limit of a query is 0 or more, but it is " + limit + ".");
            }
            this.limit = OptionalInt.of(limit);
            return this;
        }

        /**
         * Builds the query.
         *
         * @return the query
         */
        public Query build() {
            return new Query(new Start(entityType, binding, exactType, ids), steps,
                    grouped ? Optional.of(new Grouping(keys, aggregates)) : Optional.empty(), columns, order, limit);
        }
    }
}
