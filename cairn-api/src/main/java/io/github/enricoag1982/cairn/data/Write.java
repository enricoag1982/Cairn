package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One change of a {@link Transaction} (capabilities {@code entity-versions} and {@code relationships}): create an
 * entity, relate two entities, or patch, replace or end-date an entity or a relationship for an effective period.
 *
 * <p>Attribute values are keyed by the attribute's name or one of its aliases. The records only hold what the caller
 * wrote; the store checks it at commit and reports every problem found. Collections are copied and keep their
 * iteration order, which is the order in which problems about their elements are reported.
 */
public sealed interface Write {

    /**
     * Creates an entity of a concrete entity type, effective for a period. The type of an entity never changes.
     *
     * @param temporaryId the name by which other writes of the transaction refer to the entity, if it has one
     * @param id          the ID to give the entity, in the format of the store's {@link IdGenerator}; empty to let the
     *                    store generate one
     * @param entityType  the name or an alias of the entity type, compared without regard to case
     * @param period      when the entity is effective
     * @param values      the values of its attributes
     */
    record Create(
            Optional<TemporaryId> temporaryId,
            Optional<EntityId> id,
            String entityType,
            EffectivePeriod period,
            Map<String, Value> values) implements Write {

        /**
         * Rejects null components and copies the values.
         */
        public Create {
            Objects.requireNonNull(temporaryId, "temporaryId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(period, "period");
            values = Copies.map(values, "values");
        }
    }

    /**
     * Relates a source entity to a target entity with a relationship type, for a period (capability
     * {@code relationships}). The type and the ends of a relationship never change. The ends are entities of the
     * relationship type's source and target types or of their subtypes, and exist throughout the period.
     *
     * @param temporaryId      the name by which other writes of the transaction refer to the relationship, if it has
     *                         one; entities and relationships share one namespace of temporary IDs
     * @param id               the ID to give the relationship, in the format of the store's {@link IdGenerator}; empty
     *                         to let the store generate one
     * @param relationshipType the name or an alias of the relationship type, compared without regard to case
     * @param source           the source entity, by its ID or by the temporary ID of an entity that an earlier write of
     *                         the transaction creates; for a symmetric relationship type either end can be the source
     * @param target           the target entity, by its ID or by the temporary ID of an entity that an earlier write of
     *                         the transaction creates
     * @param period           when the relationship is effective
     * @param values           the values of its attributes
     */
    record Relate(
            Optional<TemporaryId> temporaryId,
            Optional<RelationshipId> id,
            String relationshipType,
            Ref source,
            Ref target,
            EffectivePeriod period,
            Map<String, Value> values) implements Write {

        /**
         * Rejects null components and copies the values.
         */
        public Relate {
            Objects.requireNonNull(temporaryId, "temporaryId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(relationshipType, "relationshipType");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(period, "period");
            values = Copies.map(values, "values");
        }
    }

    /**
     * Sets some attributes of an entity or a relationship for a period and leaves the others unchanged. It may also
     * clear optional attributes. The entity or relationship must exist throughout the period.
     *
     * @param target the entity or relationship, by its ID or its temporary ID
     * @param period the period the patch applies to
     * @param set    the attributes to set, with their new values
     * @param clear  the optional attributes to clear, by name or alias
     * @param check  the conflict check
     */
    record Patch(
            Ref target,
            EffectivePeriod period,
            Map<String, Value> set,
            Set<String> clear,
            ConflictCheck check) implements Write {

        /**
         * Rejects null components and copies the collections.
         */
        public Patch {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(period, "period");
            set = Copies.map(set, "set");
            clear = Copies.set(clear, "clear");
            Objects.requireNonNull(check, "check");
        }
    }

    /**
     * Sets the complete state of an entity or a relationship for a period: attributes that the write does not mention
     * have no value in the period. The entity or relationship must exist throughout the period.
     *
     * @param target the entity or relationship, by its ID or its temporary ID
     * @param period the period the replacement applies to
     * @param values the complete values of the entity's attributes
     * @param check  the conflict check
     */
    record Replace(Ref target, EffectivePeriod period, Map<String, Value> values, ConflictCheck check)
            implements Write {

        /**
         * Rejects null components and copies the values.
         */
        public Replace {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(period, "period");
            values = Copies.map(values, "values");
            Objects.requireNonNull(check, "check");
        }
    }

    /**
     * Ends an entity or a relationship from an instant in effective time: from then on, views do not find it. Nothing
     * is deleted. Ending an entity also ends its composition relationships and their targets from the same instant
     * (capability {@code relationships}).
     *
     * @param target the entity or relationship, by its ID or its temporary ID
     * @param from   the first instant at which it no longer exists; truncated to microseconds
     * @param check  the conflict check
     */
    record EndDate(Ref target, Instant from, ConflictCheck check) implements Write {

        /**
         * Rejects null components and truncates the instant to microseconds.
         */
        public EndDate {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(from, "from");
            from = from.truncatedTo(ChronoUnit.MICROS);
            Objects.requireNonNull(check, "check");
        }
    }
}
