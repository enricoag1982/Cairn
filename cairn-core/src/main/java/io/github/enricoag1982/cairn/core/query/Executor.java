package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.EntityType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * One row for each entity of the start type effective at the view's time, or of exactly that type; or, if the query
     * gives a list of IDs, for each of those entities that is of the type and effective, which the view reads one by one
     * (design D3 of {@code add-query-and-traversal-conveniences}), so that a rule's view records the reads of those
     * entities and not of the type. An empty list reads nothing and binds nothing.
     */
    private static List<Cell[]> start(View view, Plan plan) {
        List<Cell[]> rows = new ArrayList<>();
        for (EntityState state : plan.ids().isEmpty() ? view.entities(plan.startType()) : given(view, plan, plan.ids().get())) {
            if (!plan.exactType() || state.entityType().equals(plan.startType())) {
                Cell[] row = new Cell[plan.slots()];
                row[0] = new Cell.EntityCell(state);
                rows.add(row);
            }
        }
        return rows;
    }

    /** The entities with the given IDs, each once, that are effective and of the start type or its subtypes. */
    private static List<EntityState> given(View view, Plan plan, List<EntityId> ids) {
        Set<String> types = new HashSet<>();
        types.add(plan.startType());
        view.schema().entityType(plan.startType()).map(EntityType::subtypes).ifPresent(types::addAll);
        List<EntityState> states = new ArrayList<>();
        for (EntityId id : new LinkedHashSet<>(ids)) {
            view.entity(id).filter(state -> types.contains(state.entityType())).ifPresent(states::add);
        }
        return states;
    }
}
