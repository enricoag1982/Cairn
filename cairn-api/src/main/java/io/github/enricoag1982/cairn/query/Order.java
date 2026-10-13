package io.github.enricoag1982.cairn.query;

import java.util.Objects;

/**
 * One part of the order of a query's rows (ADR-0023, capability {@code queries}): a column, ascending or descending. A
 * binding is ordered by its ID. Missing values and empty bindings come first when ascending, so last when descending;
 * booleans come false first. After the orders a query gives, rows are ordered by the IDs of their bindings in the order
 * they were bound, or for grouped rows by their group keys.
 *
 * @param column     what to order by; the column need not be one of the query's columns, but it must be available to the
 *                   query: a binding, an attribute or a depth in an ungrouped query, a key or an aggregate in a grouped
 *                   one
 * @param descending {@code true} for the largest first
 */
public record Order(Column column, boolean descending) {

    /**
     * Rejects a null column.
     */
    public Order {
        Objects.requireNonNull(column, "column");
    }

    /**
     * Orders by a column, smallest first.
     *
     * @param column the column
     * @return the order
     */
    public static Order ascending(Column column) {
        return new Order(column, false);
    }

    /**
     * Orders by a column, largest first.
     *
     * @param column the column
     * @return the order
     */
    public static Order descending(Column column) {
        return new Order(column, true);
    }
}
