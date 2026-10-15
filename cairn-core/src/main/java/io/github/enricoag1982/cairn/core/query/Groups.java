package io.github.enricoag1982.cairn.core.query;

import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The grouping of a checked query (design D2). Rows with the same keys form a group, in the order the keys first appear.
 * Without keys, all rows form one group, which exists even if there are no rows; with keys, a group exists only for keys
 * that rows have.
 *
 * <p>A group becomes a row with a cell for every key, then for every aggregate. A key's cell is that of the first row of
 * the group. Sums are exact, averages are rounded to 34 significant digits, and minimums and maximums keep the value type
 * of the attribute. A value of another kind than the attribute's is left out of the aggregates, so that a query that
 * passed the checks never fails on what the data holds.
 *
 * @param keys       the keys: what each reads out of a row is the cell that it gives to a group, and tells groups apart
 * @param aggregates the aggregates
 */
record Groups(List<Plan.Pick> keys, List<Computed> aggregates) {

    /**
     * An aggregate.
     *
     * @param function what it computes
     * @param fetch    the attribute to aggregate; null for a count of rows
     * @param kind     the kind of values that the attribute has, which the checked query relies on; a value of another
     *                 kind is left out, so that a query that passed the checks never fails on what the data holds. Null for
     *                 a count of rows
     */
    record Computed(Aggregate.Function function, Plan.Fetch fetch, Comparing.Category kind) {
    }

    /** What stands for a key that has no value, or whose binding is empty. */
    private static final Object MISSING = new Object();

    /** Groups rows and computes the aggregates; the rows come in a fixed order, so that the result does. */
    List<Cell[]> group(List<Cell[]> rows) {
        Map<List<Object>, List<Cell[]>> groups = new LinkedHashMap<>();
        for (Cell[] row : rows) {
            List<Object> identity = new ArrayList<>();
            for (Plan.Pick key : keys) {
                identity.add(identity(key.of(row)));
            }
            groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(row);
        }
        if (keys.isEmpty() && groups.isEmpty()) {
            groups.put(List.of(), List.of());
        }
        List<Cell[]> result = new ArrayList<>();
        for (List<Cell[]> group : groups.values()) {
            Cell[] grouped = new Cell[keys.size() + aggregates.size()];
            for (int i = 0; i < keys.size(); i++) {
                grouped[i] = group.isEmpty() ? Cells.EMPTY : keys.get(i).of(group.getFirst());
            }
            for (int i = 0; i < aggregates.size(); i++) {
                grouped[keys.size() + i] = compute(aggregates.get(i), group);
            }
            result.add(grouped);
        }
        return result;
    }

    /** What tells a key's cells apart: the ID of an entity or relationship, or the value, numbers by their numeric value. */
    private static Object identity(Cell cell) {
        return switch (cell) {
            case Cell.EntityCell entity -> entity.state().id();
            case Cell.RelationshipCell relationship -> relationship.state().id();
            case Cell.ValueCell value -> switch (value.value()) {
                case Value.IntValue number -> BigDecimal.valueOf(number.value()).stripTrailingZeros();
                case Value.LongValue number -> BigDecimal.valueOf(number.value()).stripTrailingZeros();
                case Value.DecimalValue number -> number.value().stripTrailingZeros();
                default -> value.value();
            };
            case Cell.Empty ignored -> MISSING;
            case null -> MISSING;
        };
    }

    private static Cell compute(Computed aggregate, List<Cell[]> rows) {
        if (aggregate.fetch() == null) {
            return Cells.of(Value.of((long) rows.size()));
        }
        List<Value> values = new ArrayList<>();
        for (Cell[] row : rows) {
            Value value = aggregate.fetch().of(row);
            if (value != null && Comparing.category(value.valueType()) == aggregate.kind()) {
                values.add(value);
            }
        }
        return switch (aggregate.function()) {
            case COUNT -> Cells.of(Value.of((long) values.size()));
            case SUM -> Cells.of(Value.of(sum(values)));
            case AVG -> values.isEmpty() ? Cells.EMPTY
                    : Cells.of(Value.of(sum(values).divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128)));
            case MIN -> extreme(values, -1);
            case MAX -> extreme(values, 1);
        };
    }

    private static BigDecimal sum(List<Value> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Value value : values) {
            sum = sum.add(Comparing.number(value));
        }
        return sum;
    }

    /** The smallest (direction -1) or largest (1) value, as it is; the first of equal values. */
    private static Cell extreme(List<Value> values, int direction) {
        Value found = null;
        for (Value value : values) {
            if (found == null || Comparing.order(value, found) * direction > 0) {
                found = value;
            }
        }
        return Cells.of(found);
    }
}
