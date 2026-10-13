package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.Objects;

/**
 * One value of a {@link Row} (ADR-0023, capability {@code queries}): the state of an entity or of a relationship at the
 * view's coordinates, a value, or nothing.
 */
public sealed interface Cell {

    /**
     * The state of a bound entity.
     *
     * @param state the state, as the view that ran the query reads it
     */
    record EntityCell(EntityState state) implements Cell {

        /**
         * Rejects a null state.
         */
        public EntityCell {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * The state of a bound relationship.
     *
     * @param state the state, as the view that ran the query reads it
     */
    record RelationshipCell(RelationshipState state) implements Cell {

        /**
         * Rejects a null state.
         */
        public RelationshipCell {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * A value: of an attribute, a depth (an int), a group key, or an aggregate (a long for a count, a decimal for a sum
     * or an average, and the attribute's value type for a minimum or a maximum).
     *
     * @param value the value
     */
    record ValueCell(Value value) implements Cell {

        /**
         * Rejects a null value.
         */
        public ValueCell {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Nothing: an empty binding of an optional follow, an attribute without a value, or an aggregate without a value.
     */
    record Empty() implements Cell {
    }

    /**
     * The empty cell.
     *
     * @return a cell with nothing in it
     */
    static Cell empty() {
        return new Empty();
    }
}
