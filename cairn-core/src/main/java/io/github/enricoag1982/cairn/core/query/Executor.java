package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a checked query against a view (design D2 and D3): the start, then each step over the rows so far, then grouping,
 * the order, the limit, and the columns. It reads only through the view, and keeps nothing after the run.
 */
final class Executor {

    private Executor() {
    }

    /** Runs a plan; the plan was checked against the schema of the view. */
    static QueryResult.Rows run(View view, Plan plan) {
        Context context = new Context(view);
        List<Cell[]> rows = start(view, plan);
        for (Stage stage : plan.stages()) {
            rows = stage.apply(rows, context);
        }
        if (plan.groups() != null) {
            // A fixed order of the rows makes a group's first row, and so its key cells, the same whatever stores the data.
            rows.sort(plan.rowOrder());
            rows = plan.groups().group(rows);
        }
        rows.sort(plan.order());
        if (plan.limit().isPresent() && plan.limit().getAsInt() < rows.size()) {
            rows = rows.subList(0, plan.limit().getAsInt());
        }
        List<Row> result = new ArrayList<>();
        for (Cell[] row : rows) {
            Map<String, Cell> cells = new LinkedHashMap<>();
            for (int i = 0; i < plan.columns().size(); i++) {
                cells.put(plan.columnNames().get(i), plan.columns().get(i).of(row));
            }
            result.add(new Row(cells));
        }
        return new QueryResult.Rows(view.effectiveAt(), view.basis(), plan.columnNames(), result);
    }

    /** One row for each entity of the start type effective at the view's time, or of exactly that type. */
    private static List<Cell[]> start(View view, Plan plan) {
        List<Cell[]> rows = new ArrayList<>();
        for (EntityState state : view.entities(plan.startType())) {
            if (!plan.exactType() || state.entityType().equals(plan.startType())) {
                Cell[] row = new Cell[plan.slots()];
                row[0] = new Cell.EntityCell(state);
                rows.add(row);
            }
        }
        return rows;
    }
}
