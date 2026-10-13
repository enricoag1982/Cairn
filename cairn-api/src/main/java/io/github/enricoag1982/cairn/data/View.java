package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * An immutable view of a store, bound to {@linkplain Coordinates coordinates} when it is created (ADR-0013,
 * capability {@code views}). Every read through one view returns the same answer, whatever is committed later, and
 * never waits for a writer.
 *
 * <p>Values are reported under the attribute names of the view's {@linkplain #schema() schema}, in the order of its
 * attributes, and in the attribute's value type in that schema.
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
}
