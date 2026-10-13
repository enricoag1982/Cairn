package io.github.enricoag1982.cairn.data;

import io.github.enricoag1982.cairn.schema.Basis;
import java.util.Objects;

/**
 * What a write asks to be checked against the state its caller read (ADR-0009, capability {@code entity-versions}).
 * Without a check, the last commit wins.
 *
 * <p>A check compares the entity as known at the basis its caller read it with the entity as known at the latest
 * basis. A per-entity check rejects the write with the problem {@code CONFLICT} if the entity differs in any way; a
 * per-attribute check does so only if an attribute that the write sets or clears differs.
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
     * Rejects the write if any transaction after the basis changed the entity.
     *
     * @param readAt the basis at which the caller read the entity
     * @return the check
     */
    static ConflictCheck perEntity(Basis readAt) {
        return new PerEntity(readAt);
    }

    /**
     * Rejects the write if any transaction after the basis changed an attribute that the write sets or clears.
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
     * A check of the attributes that the write sets or clears.
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
