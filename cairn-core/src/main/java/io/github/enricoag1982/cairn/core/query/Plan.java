package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

/**
 * A query that was checked against a schema, ready to run: names are resolved, and every condition, column and order is
 * a function of a row (design D2 and D3). A row is an array of cells, one for every binding in the order they are bound;
 * the rows that a grouped query returns have a cell for every key and then every aggregate.
 *
 * @param startType   the name of the entity type to start from, as the schema spells it
 * @param exactType   whether to take only the entities of exactly that type
 * @param slots       the number of cells of a row before grouping; the start binds the first
 * @param stages      the steps after the start
 * @param groups      how to group the rows, or null for a query without grouping
 * @param columnNames the names of the columns to return
 * @param columns     what each column reads out of a row
 * @param rowOrder    the order of rows before grouping: the IDs of their bindings in the order they are bound
 * @param order       the order of the rows to return: the order the query gives, then the IDs of the bindings or, for
 *                    grouped rows, the keys
 * @param limit       the largest number of rows to return
 */
record Plan(
        String startType,
        boolean exactType,
        int slots,
        List<Stage> stages,
        Groups groups,
        List<String> columnNames,
        List<Pick> columns,
        Comparator<Cell[]> rowOrder,
        Comparator<Cell[]> order,
        OptionalInt limit) {

    /** Reads a value out of a row; null where it is missing: no value, or an empty binding. */
    @FunctionalInterface
    interface Fetch {
        Value of(Cell[] row);
    }

    /** Tells whether a condition holds for a row. */
    @FunctionalInterface
    interface Test {
        boolean holds(Cell[] row, Context context);
    }

    /** Reads a cell out of a row. */
    @FunctionalInterface
    interface Pick {
        Cell of(Cell[] row);
    }
}
