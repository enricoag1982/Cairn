package io.github.enricoag1982.cairn.core.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.Comparison;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What aggregates and comparisons do with a value that is not of the kind the checked query relies on (design D2 of
 * {@code add-queries-and-aggregation}): it is left out, or compares false, and nothing fails, so that a query that passed
 * the checks never throws whatever the data holds.
 */
class GroupsTest {

    /** Rows with one cell each, holding the values, and an empty cell for a missing value. */
    private static List<Cell[]> rowsOf(Value... values) {
        List<Cell[]> rows = new ArrayList<>();
        for (Value value : values) {
            rows.add(new Cell[] {Cells.of(value)});
        }
        return rows;
    }

    private static Cell aggregate(Aggregate.Function function, Comparing.Category kind, Value... values) {
        Groups groups = new Groups(List.of(), List.of(new Groups.Computed(function, row -> switch (row[0]) {
            case Cell.ValueCell cell -> cell.value();
            default -> null;
        }, kind)));
        List<Cell[]> grouped = groups.group(rowsOf(values));
        assertThat(grouped).singleElement();
        return grouped.getFirst()[0];
    }

    private static final Value TEXT = Value.of("large");
    private static final Value DATE = Value.of(LocalDate.of(2026, 3, 31));

    @Test
    void aSumLeavesOutValuesThatAreNoNumbers() {
        Cell sum = aggregate(Aggregate.Function.SUM, Comparing.Category.NUMBER, Value.of(5), TEXT, Value.of(2L), DATE,
                Value.of(new BigDecimal("1.5")), Value.of(true));

        assertThat(sum).isEqualTo(Cells.of(Value.of(new BigDecimal("8.5"))));
    }

    @Test
    void anAverageLeavesOutValuesThatAreNoNumbers() {
        Cell average = aggregate(Aggregate.Function.AVG, Comparing.Category.NUMBER, Value.of(4), TEXT, Value.of(6));
        Cell none = aggregate(Aggregate.Function.AVG, Comparing.Category.NUMBER, TEXT, DATE);

        assertThat(average).isEqualTo(Cells.of(Value.of(new BigDecimal("5"))));
        assertThat(none).as("no number is left, so there is no average").isEqualTo(Cells.EMPTY);
    }

    @Test
    void aMinimumAndAMaximumLeaveOutValuesOfAnotherKindThanTheAttributeHas() {
        assertThat(aggregate(Aggregate.Function.MIN, Comparing.Category.NUMBER, TEXT, Value.of(7), Value.of(3), DATE))
                .isEqualTo(Cells.of(Value.of(3)));
        assertThat(aggregate(Aggregate.Function.MAX, Comparing.Category.STRING, Value.of(7), TEXT, Value.of("a")))
                .isEqualTo(Cells.of(TEXT));
        assertThat(aggregate(Aggregate.Function.MAX, Comparing.Category.DATE, TEXT, Value.of(7)))
                .isEqualTo(Cells.EMPTY);
    }

    @Test
    void aCountOfAnAttributeCountsOnlyTheValuesOfTheKindTheAttributeHas() {
        assertThat(aggregate(Aggregate.Function.COUNT, Comparing.Category.STRING, TEXT, Value.of(7), Value.of("a"),
                DATE)).isEqualTo(Cells.of(Value.of(2L)));
    }

    @Test
    void aCountOfRowsCountsEveryRow() {
        Groups groups = new Groups(List.of(), List.of(new Groups.Computed(Aggregate.Function.COUNT, null, null)));

        assertThat(groups.group(rowsOf(TEXT, Value.of(7), null)).getFirst()[0]).isEqualTo(Cells.of(Value.of(3L)));
    }

    @Test
    void valuesOfDifferentKindsDoNotCompareAndAreNeverEqual() {
        for (Comparison comparison : Comparison.values()) {
            boolean holds = Comparing.holds(Value.of(5), comparison, TEXT);

            assertThat(holds).as("5 %s 'large'", comparison).isFalse();
        }
        assertThat(Comparing.equal(Value.of(5), TEXT)).isFalse();
        assertThat(Comparing.equal(TEXT, Value.of(5))).isFalse();
        assertThat(Comparing.equal(DATE, Value.of(5))).isFalse();
        assertThat(Comparing.orderMissingFirst(Value.of(5), TEXT)).isNotZero();
    }
}
