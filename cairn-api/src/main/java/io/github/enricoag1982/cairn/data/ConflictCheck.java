package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.util.Objects;

/**
 * What a write asks to be checked against the state its caller read (ADR-0009, capability {@code entity-versions}).
 * Without a check, the last commit wins.
 *
 * <p>A check compares the entity's state as known at the basis its caller read it with its state as known at the
 * latest basis. The comparison is by state: the values the entity has at every point in effective time, whichever
 * transactions recorded them, so an entity that later transactions changed and changed back has not changed. A
 * per-entity check rejects the write with the problem {@code CONFLICT} if the entity's state differs in any way. A
 * per-attribute check does so only if the state of an attribute that the write sets or clears differs; a replace or
 * an end-date concerns every attribute, so a per-attribute check of one compares the whole entity.
 */
public sealed interface ConflictCheck {

    /**
     * No check: the write wins over any change made since the caller read the entity.
     *
     * @return the check
     */
    static ConflictCheck none() {
        return new None();
    }

    /**
     * Rejects the write if the entity's state differs from its state at the basis.
     *
     * @param readAt the basis at which the caller read the entity
     * @return the check
     */
    static ConflictCheck perEntity(Basis readAt) {
        return new PerEntity(readAt);
    }

    /**
     * Rejects the write if the state of an attribute that the write sets or clears differs from its state at the
     * basis. For a replace or an end-date, which concern every attribute, it compares the whole entity.
     *
     * @param readAt the basis at which the caller read the entity
     * @return the check
     */
    static ConflictCheck perAttribute(Basis readAt) {
        return new PerAttribute(readAt);
    }

    /**
     * No check.
     */
    record None() implements ConflictCheck {
    }

    /**
     * A check of the whole entity.
     *
     * @param readAt the basis at which the caller read the entity
     */
    record PerEntity(Basis readAt) implements ConflictCheck {
        /**
         * Rejects a null basis.
         */
        public PerEntity {
            Objects.requireNonNull(readAt, "readAt");
        }
    }

    /**
     * A check of the attributes that the write sets or clears; of the whole entity for a replace or an end-date.
     *
     * @param readAt the basis at which the caller read the entity
     */
    record PerAttribute(Basis readAt) implements ConflictCheck {
        /**
         * Rejects a null basis.
         */
        public PerAttribute {
            Objects.requireNonNull(readAt, "readAt");
        }
    }
}
