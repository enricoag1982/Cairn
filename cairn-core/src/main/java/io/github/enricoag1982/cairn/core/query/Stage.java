package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;

/**
 * A step of a checked query, ready to run: it takes the rows that the steps before it produced and gives its own. A row is
 * an array with a cell for every binding of the query, in the order they are bound; a step fills the cells of its own
 * bindings (design D2).
 */
sealed interface Stage {

    /** Applies the step to rows; the rows given are not changed. */
    List<Cell[]> apply(List<Cell[]> rows, Context context);

    /** Keeps the rows for which a test holds. */
    record Filter(Plan.Test test) implements Stage {
        @Override
        public List<Cell[]> apply(List<Cell[]> rows, Context context) {
            List<Cell[]> kept = new ArrayList<>();
            for (Cell[] row : rows) {
                if (test.holds(row, context)) {
                    kept.add(row);
                }
            }
            return kept;
        }
    }

    /**
     * Binds the entity and the relationship at the end of each link that leads away from a bound entity. An optional
     * follow keeps a row without links, with the two bindings empty.
     */
    record Follow(int from, String name, int entity, int relationship, boolean optional) implements Stage {
        @Override
        public List<Cell[]> apply(List<Cell[]> rows, Context context) {
            List<Cell[]> result = new ArrayList<>();
            for (Cell[] row : rows) {
                List<Link> links = row[from] instanceof Cell.EntityCell bound
                        ? context.navigate(bound.state().id(), name) : List.of();
                if (links.isEmpty() && optional) {
                    result.add(extend(row, entity, Cells.EMPTY, relationship, Cells.EMPTY, -1, null));
                }
                for (Link link : links) {
                    result.add(extend(row, entity, new Cell.EntityCell(link.entity()), relationship,
                            new Cell.RelationshipCell(link.relationship()), -1, null));
                }
            }
            return result;
        }
    }

    /**
     * Binds each entity reached from a bound entity at depths from a minimum to a maximum, with the relationship it was
     * reached through and its depth. Depth 0 is the bound entity itself.
     */
    record Traverse(int from, String name, int entity, int relationship, int depth, int minDepth, int maxDepth)
            implements Stage {
        @Override
        public List<Cell[]> apply(List<Cell[]> rows, Context context) {
            List<Cell[]> result = new ArrayList<>();
            for (Cell[] row : rows) {
                if (!(row[from] instanceof Cell.EntityCell bound)) {
                    continue;
                }
                if (minDepth == 0) {
                    result.add(extend(row, entity, bound, relationship, Cells.EMPTY, depth, new Cell.ValueCell(Value.of(0))));
                }
                if (maxDepth >= 1) {
                    EntityId start = bound.state().id();
                    for (Step step : context.traverse(start, name, maxDepth)) {
                        if (step.depth() >= minDepth) {
                            result.add(extend(row, entity, new Cell.EntityCell(step.entity()), relationship,
                                    new Cell.RelationshipCell(step.via()), depth,
                                    new Cell.ValueCell(Value.of(step.depth()))));
                        }
                    }
                }
            }
            return result;
        }
    }

    /** A copy of a row with the cells of a step's bindings set; a depth slot below 0 means there is none. */
    private static Cell[] extend(Cell[] row, int entity, Cell entityCell, int relationship, Cell relationshipCell,
            int depth, Cell depthCell) {
        Cell[] copy = row.clone();
        copy[entity] = entityCell;
        copy[relationship] = relationshipCell;
        if (depth >= 0) {
            copy[depth] = depthCell;
        }
        return copy;
    }
}
