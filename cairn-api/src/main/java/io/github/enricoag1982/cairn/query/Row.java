package io.github.enricoag1982.cairn.query;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of a query's result (ADR-0023, capability {@code queries}): a {@link Cell} for each column, in the order of the
 * columns.
 *
 * <p>The accessors by column name fail with an {@link IllegalArgumentException} if the row has no column of that name,
 * or if the column holds another kind of cell than the accessor reads; an empty cell is an empty {@link Optional}.
 *
 * @param cells the cells, keyed by the names of the columns and in their order
 */
public record Row(Map<String, Cell> cells) {

    /**
     * Rejects null components and copies the cells, keeping their order.
     */
    public Row {
        Objects.requireNonNull(cells, "cells");
        Map<String, Cell> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "cells key"),
                    Objects.requireNonNull(entry.getValue(), "cells value"));
        }
        cells = Collections.unmodifiableMap(copy);
    }

    /**
     * Reads a cell.
     *
     * @param column the name of the column
     * @return the cell
     * @throws IllegalArgumentException if the row has no such column
     */
    public Cell cell(String column) {
        Objects.requireNonNull(column, "column");
        Cell cell = cells.get(column);
        if (cell == null) {
            throw new IllegalArgumentException("The row has no column '" + column + "'; its columns are "
                    + cells.keySet() + ".");
        }
        return cell;
    }

    /**
     * Reads the state of a bound entity.
     *
     * @param column the name of the column
     * @return the state; empty if the binding is empty
     * @throws IllegalArgumentException if the row has no such column, or it holds no entity
     */
    public Optional<EntityState> entity(String column) {
        return switch (cell(column)) {
            case Cell.EntityCell cell -> Optional.of(cell.state());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "an entity");
        };
    }

    /**
     * Reads the state of a bound relationship.
     *
     * @param column the name of the column
     * @return the state; empty if the binding is empty
     * @throws IllegalArgumentException if the row has no such column, or it holds no relationship
     */
    public Optional<RelationshipState> relationship(String column) {
        return switch (cell(column)) {
            case Cell.RelationshipCell cell -> Optional.of(cell.state());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a relationship");
        };
    }

    /**
     * Reads a value.
     *
     * @param column the name of the column
     * @return the value; empty if there is none
     * @throws IllegalArgumentException if the row has no such column, or it holds an entity or a relationship
     */
    public Optional<Value> value(String column) {
        return switch (cell(column)) {
            case Cell.ValueCell cell -> Optional.of(cell.value());
            case Cell.Empty ignored -> Optional.empty();
            default -> throw wrongKind(column, "a value");
        };
    }

    private IllegalArgumentException wrongKind(String column, String wanted) {
        return new IllegalArgumentException("The column '" + column + "' holds " + cells.get(column).getClass()
                .getSimpleName() + ", not " + wanted + ".");
    }
}
