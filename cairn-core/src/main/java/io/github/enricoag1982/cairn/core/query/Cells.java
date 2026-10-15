package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.schema.Value;

/** What the evaluator reads out of the cells of a row. */
final class Cells {

    /** The cell with nothing in it. */
    static final Cell EMPTY = Cell.empty();

    private Cells() {
    }

    /** A cell for a value that may be missing. */
    static Cell of(Value value) {
        return value == null ? EMPTY : new Cell.ValueCell(value);
    }

    /** The cell, or the empty cell where a row has none. */
    static Cell orEmpty(Cell cell) {
        return cell == null ? EMPTY : cell;
    }

    /** The value of an attribute of a bound entity or relationship; null if there is none, or the binding is empty. */
    static Value attribute(Cell cell, String attribute) {
        return switch (cell) {
            case Cell.EntityCell entity -> entity.state().values().get(attribute);
            case Cell.RelationshipCell relationship -> relationship.state().values().get(attribute);
            case null, default -> null;
        };
    }

    /** The ID of a bound entity or relationship as a string value; null if the binding is empty. */
    static Value id(Cell cell) {
        return switch (cell) {
            case Cell.EntityCell entity -> Value.of(entity.state().id().value());
            case Cell.RelationshipCell relationship -> Value.of(relationship.state().id().value());
            case null, default -> null;
        };
    }

    /** The exact type of a bound entity or relationship as a string value; null if the binding is empty. */
    static Value type(Cell cell) {
        return switch (cell) {
            case Cell.EntityCell entity -> Value.of(entity.state().entityType());
            case Cell.RelationshipCell relationship -> Value.of(relationship.state().relationshipType());
            case null, default -> null;
        };
    }

    /**
     * What a cell is ordered by: the ID of an entity or relationship, or the value; null for an empty cell, which comes
     * first.
     */
    static Value sortValue(Cell cell) {
        return switch (cell) {
            case Cell.ValueCell value -> value.value();
            case null, default -> id(cell);
        };
    }
}
