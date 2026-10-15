package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * An immutable view of a store, bound to {@linkplain Coordinates coordinates} when it is created (ADR-0013,
 * capability {@code views}). Every read through one view returns the same answer, whatever is committed later, and
 * never waits for a writer.
 *
 * <p>Values, of entities and of relationships, are reported under the attribute names of the view's
 * {@linkplain #schema() schema}, in the order of its attributes, and in the attribute's value type in that schema.
 */
public interface View {

    /**
     * The effective time of this view.
     *
     * @return the effective time, with microsecond precision
     */
    Instant effectiveAt();

    /**
     * The basis this view reads as of: the snapshot of the schema and the data it sees.
     *
     * @return the basis; for a view known at a transaction time, the basis of the latest transaction recorded at or
     *         before that time
     */
    Basis basis();

    /**
     * The schema as it was at this view's basis.
     *
     * @return the schema
     */
    Schema schema();

    /**
     * Reads an entity at the view's effective time, as known at its basis.
     *
     * @param id the entity's ID
     * @return the entity's state; empty if the entity does not exist at the effective time, or was not yet recorded
     *         at the basis
     */
    Optional<EntityState> entity(EntityId id);

    /**
     * Finds the entity that has a unique key at the view's effective time, as known at its basis (ADR-0026, capability
     * {@code views}): the entity of the key's type, or of one of the type's subtypes, whose value of the key's attribute
     * is the key's value then. Unique attributes make the answer at most one entity at an instant, though over time the
     * same value can belong to different entities, so a view at another effective time may find another one.
     *
     * @param key the entity type, a unique attribute of it and a value; names and aliases are compared without regard
     *            to case, and a number fits a wider numeric attribute
     * @return the entity's state, as {@link #entity(EntityId)} reads it; empty if no entity has the value at the
     *         effective time
     * @throws IllegalArgumentException if the view's schema has no entity type of that name, the type has no attribute of
     *                                  that name, the attribute is not unique, or the value does not fit its value type
     */
    Optional<EntityState> entity(UniqueKey key);

    /**
     * Lists the entities of an entity type that exist at the view's effective time, including the entities of the
     * type's subtypes.
     *
     * @param entityType the name or an alias of the entity type in the view's schema, compared without regard to case
     * @return the states of the entities, ordered by ID
     * @throws IllegalArgumentException if the view's schema has no entity type of that name
     */
    List<EntityState> entities(String entityType);

    /**
     * Reads the timeline of an entity as known at the view's basis: every version across effective time. The view's
     * effective time does not matter.
     *
     * @param id the entity's ID
     * @return the versions, ordered by the start of their effective periods; empty if the entity was not recorded at
     *         the basis
     */
    List<EntityState> timeline(EntityId id);

    /**
     * Reads a relationship at the view's effective time, as known at its basis.
     *
     * @param id the relationship's ID
     * @return the relationship's state; empty if the relationship does not exist at the effective time, was not yet
     *         recorded at the basis, or the ID is not that of a relationship
     */
    Optional<RelationshipState> relationship(RelationshipId id);

    /**
     * Reads the timeline of a relationship as known at the view's basis: every version across effective time. The
     * view's effective time does not matter.
     *
     * @param id the relationship's ID
     * @return the versions, ordered by the start of their effective periods; empty if the relationship was not recorded
     *         at the basis
     */
    List<RelationshipState> relationshipTimeline(RelationshipId id);

    /**
     * Navigates from an entity along the relationships that exist at the view's effective time. A relationship type's
     * name (or one of its aliases) leads from the source of its relationships to their targets, and its inverse name (or
     * one of its inverse aliases) from the target to the source; relationships of the type's subtypes are included.
     * For a symmetric relationship type either name leads from both ends. Names are compared without regard to case.
     *
     * @param from the entity to navigate from; one that does not exist at the effective time has no relationships
     * @param name the name of a relationship type or the inverse name of one in the view's schema, such as {@code Owns}
     *             or {@code ownedBy}
     * @return the relationships and the entities at their other ends, ordered by the ID of the relationship; each
     *         relationship at most once
     * @throws IllegalArgumentException if the view's schema has no relationship type or inverse name of that name
     */
    List<Link> navigate(EntityId from, String name);

    /**
     * Navigates like {@link #navigate}, but across effective time: every version of every relationship that leads away
     * from the entity by the name at some effective time, as known at the view's basis. The view's effective time does
     * not matter. A rule that checks a sum over the relationships of an entity reads the instants at which they start,
     * end or change from the periods of these versions.
     *
     * @param from the entity to navigate from
     * @param name the name of a relationship type or the inverse name of one, as for {@link #navigate}
     * @return the versions of the relationships, ordered by the ID of the relationship and then by the start of the
     *         version's period; empty if the entity has no such relationship at any time
     * @throws IllegalArgumentException if the view's schema has no relationship type or inverse name of that name
     */
    List<RelationshipState> navigateTimeline(EntityId from, String name);

    /**
     * Traverses relationships recursively from an entity, breadth first, along the relationships that
     * {@linkplain #navigate navigation} finds by a name and that satisfy a condition. The entities one relationship
     * away are visited first, in the order of {@link #navigate}, then those two away, and so on. Each entity is visited
     * at most once, through the first relationship that reached it, so a cycle ends the traversal; the entity the
     * traversal starts from is not part of the result, even if a cycle leads back to it.
     *
     * @param from     the entity to start from
     * @param name     the name of a relationship type or the inverse name of one, as for {@link #navigate}
     * @param follow   the condition a relationship must satisfy to be followed; it sees the relationship as this view
     *                 reads it
     * @param maxDepth the largest number of relationships to follow from the start; 0 follows none
     * @return the entities reached, each with its depth and the relationship it was reached through, in the order of
     *         the traversal
     * @throws IllegalArgumentException if the view's schema has no relationship type or inverse name of that name, or
     *                                  {@code maxDepth} is negative
     */
    List<Step> traverse(EntityId from, String name, Predicate<RelationshipState> follow, int maxDepth);

    /**
     * Runs a query against this view (ADR-0023, capability {@code queries}). The rows reflect exactly this view's
     * coordinates: the entities and relationships that are effective at its effective time, as known at its basis. The
     * query is checked against the view's {@linkplain #schema() schema} first; one that names a type, attribute,
     * relationship name or binding that does not exist, binds a name twice, compares values that cannot be compared, or
     * aggregates values that cannot be aggregated returns every problem found, and nothing is read.
     *
     * <p>The same query on the same data returns the same rows in the same order, whatever stores them.
     *
     * @param query the query
     * @return the rows, with the coordinates they were read at, or the problems of the query; it never throws for a query
     *         that does not fit the schema
     */
    QueryResult query(Query query);
}
