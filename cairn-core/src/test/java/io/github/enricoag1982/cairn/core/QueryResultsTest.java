package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ItemStore.EMOJI;
import static io.github.enricoag1982.cairn.core.ItemStore.FULL_WIDTH_A;
import static io.github.enricoag1982.cairn.core.ItemStore.I1;
import static io.github.enricoag1982.cairn.core.ItemStore.I3;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.QueryRows.texts;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What grouping, aggregates, columns, order and limits mean (design D2 of {@code add-queries-and-aggregation}), over the
 * items of {@link ItemStore}.
 */
class QueryResultsTest {

    private final ItemStore items = new ItemStore();

    private static Operand.AttributeOf item(String attribute) {
        return Operand.attribute("i", attribute);
    }

    private QueryResult.Rows run(Query query) {
        return rows(items.view().query(query));
    }

    private static List<String> labels(QueryResult.Rows result) {
        return texts(result, "i", "label");
    }

    private static Value valueOf(Row row, String column) {
        return row.value(column).orElse(null);
    }

    private static Value decimal(String text) {
        return Value.of(new BigDecimal(text));
    }

    // ------------------------------------------------------------------ aggregates

    @Test
    void everyFunctionAggregatesTheValuesOfEachGroup() {
        Query query = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.count("rows"),
                        Aggregate.count(item("quantity"), "quantities"),
                        Aggregate.sum(item("quantity"), "sumQuantity"),
                        Aggregate.sum(item("price"), "sumPrice"),
                        Aggregate.avg(item("price"), "avgPrice"),
                        Aggregate.min(item("price"), "minPrice"),
                        Aggregate.max(item("price"), "maxPrice"))
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("category", "rows", "quantities", "sumQuantity", "sumPrice",
                "avgPrice", "minPrice", "maxPrice");
        assertThat(result.rows()).hasSize(3);
        Row x = result.rows().get(0);
        assertThat(x.value("category")).contains(Value.of("x"));
        assertThat(valueOf(x, "rows")).isEqualTo(Value.of(2L));
        assertThat(valueOf(x, "quantities")).isEqualTo(Value.of(2L));
        assertThat(valueOf(x, "sumQuantity")).isEqualTo(decimal("12"));
        assertThat(valueOf(x, "sumPrice")).isEqualTo(decimal("4.00"));
        assertThat(valueOf(x, "avgPrice")).isEqualTo(decimal("2.00"));
        assertThat(valueOf(x, "minPrice")).isEqualTo(decimal("1.50"));
        assertThat(valueOf(x, "maxPrice")).isEqualTo(decimal("2.5"));
        Row y = result.rows().get(1);
        assertThat(y.value("category")).contains(Value.of("y"));
        assertThat(valueOf(y, "rows")).isEqualTo(Value.of(2L));
        assertThat(valueOf(y, "quantities")).as("delta has no quantity").isEqualTo(Value.of(1L));
        assertThat(valueOf(y, "sumQuantity")).isEqualTo(decimal("3"));
        assertThat(valueOf(y, "avgPrice")).as("missing values are left out").isEqualTo(decimal("10.00"));
        assertThat(valueOf(y, "minPrice")).isEqualTo(valueOf(y, "maxPrice")).isEqualTo(decimal("10.00"));
        Row z = result.rows().get(2);
        assertThat(valueOf(z, "rows")).isEqualTo(Value.of(2L));
        assertThat(valueOf(z, "quantities")).isEqualTo(Value.of(0L));
        assertThat(valueOf(z, "sumQuantity")).as("a sum of no values is 0").isEqualTo(decimal("0"));
        assertThat(valueOf(z, "sumPrice")).isEqualTo(decimal("0"));
        assertThat(z.cell("avgPrice")).as("no average without values").isEqualTo(Cell.empty());
        assertThat(z.cell("minPrice")).isEqualTo(Cell.empty());
        assertThat(z.cell("maxPrice")).isEqualTo(Cell.empty());
    }

    @Test
    void minimumAndMaximumKeepTheValueTypeOfTheAttribute() {
        Query query = Query.from("Item", "i").aggregate(
                Aggregate.min(item("quantity"), "minQuantity"), Aggregate.max(item("quantity"), "maxQuantity"),
                Aggregate.min(item("serial"), "minSerial"), Aggregate.max(item("serial"), "maxSerial"),
                Aggregate.min(item("born"), "firstBirth"), Aggregate.max(item("born"), "lastBirth"),
                Aggregate.min(item("seen"), "firstSeen"), Aggregate.max(item("seen"), "lastSeen"),
                Aggregate.min(item("label"), "firstLabel"), Aggregate.max(item("label"), "lastLabel"))
                .build();

        Row row = run(query).rows().getFirst();

        assertThat(valueOf(row, "minQuantity")).isEqualTo(Value.of(3));
        assertThat(valueOf(row, "maxQuantity")).isEqualTo(Value.of(7));
        assertThat(valueOf(row, "minSerial")).isEqualTo(Value.of(7L));
        assertThat(valueOf(row, "maxSerial")).isEqualTo(Value.of(5_000_000_000L));
        assertThat(valueOf(row, "firstBirth")).isEqualTo(Value.of(LocalDate.of(2020, 3, 1)));
        assertThat(valueOf(row, "lastBirth")).isEqualTo(Value.of(LocalDate.of(2021, 5, 5)));
        assertThat(valueOf(row, "firstSeen")).isEqualTo(Value.of(java.time.Instant.parse("2026-02-01T10:00:00Z")));
        assertThat(valueOf(row, "lastSeen")).isEqualTo(Value.of(java.time.Instant.parse("2026-03-01T00:00:00Z")));
        assertThat(valueOf(row, "firstLabel")).as("by code point, so Beta before alpha").isEqualTo(Value.of("Beta"));
        assertThat(valueOf(row, "lastLabel")).as("by code point, not by UTF-16 unit").isEqualTo(Value.of(EMOJI));
    }

    @Test
    void anAverageIsRoundedToThirtyFourSignificantDigits() {
        Query query = Query.from("Item", "i").aggregate(Aggregate.avg(item("price"), "average"),
                Aggregate.avg(item("quantity"), "averageQuantity")).build();

        Row row = run(query).rows().getFirst();

        BigDecimal expected = new BigDecimal("14.00").divide(BigDecimal.valueOf(3), MathContext.DECIMAL128);
        assertThat(valueOf(row, "average")).isEqualTo(Value.of(expected));
        assertThat(expected.precision()).isEqualTo(34);
        assertThat(valueOf(row, "averageQuantity")).as("(5 + 7 + 3) / 3").isInstanceOfSatisfying(Value.DecimalValue.class,
                average -> assertThat(average.value()).isEqualByComparingTo("5"));
    }

    @Test
    void aSumIsExact() {
        ReferenceStore store = ReferenceStore.with(ItemStore.definition());
        store.commit(Transaction.by("test")
                .create("Item", from(1, 1), values("label", "a", "serial", Long.MAX_VALUE, "price", money("0.1")))
                .create("Item", from(1, 1), values("label", "b", "serial", Long.MAX_VALUE, "price", money("0.2")))
                .create("Item", from(1, 1), values("label", "c", "serial", 1L,
                        "price", money("12345678901234567890.123456789")))
                .build());
        Query query = Query.from("Item", "i").aggregate(Aggregate.sum(item("serial"), "serials"),
                Aggregate.sum(item("price"), "prices")).build();

        Row row = rows(store.view(on(3, 1)).query(query)).rows().getFirst();

        assertThat(valueOf(row, "serials")).isEqualTo(decimal("18446744073709551615"));
        assertThat(valueOf(row, "prices")).isEqualTo(decimal("12345678901234567890.423456789"));
    }

    @Test
    void withoutKeysAllRowsFormOneGroupThatExistsEvenWithoutRows() {
        Query nothing = Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("none")))
                .aggregate(Aggregate.count("rows"), Aggregate.count(item("quantity"), "values"),
                        Aggregate.sum(item("quantity"), "sum"), Aggregate.min(item("quantity"), "min"),
                        Aggregate.max(item("quantity"), "max"), Aggregate.avg(item("quantity"), "avg"))
                .build();
        Query everything = Query.from("Item", "i")
                .aggregate(Aggregate.count("rows"), Aggregate.sum(item("quantity"), "sum")).build();

        QueryResult.Rows empty = run(nothing);
        QueryResult.Rows all = run(everything);

        assertThat(empty.rows()).singleElement().satisfies(row -> {
            assertThat(valueOf(row, "rows")).isEqualTo(Value.of(0L));
            assertThat(valueOf(row, "values")).isEqualTo(Value.of(0L));
            assertThat(valueOf(row, "sum")).isEqualTo(decimal("0"));
            assertThat(row.cell("min")).isEqualTo(Cell.empty());
            assertThat(row.cell("max")).isEqualTo(Cell.empty());
            assertThat(row.cell("avg")).isEqualTo(Cell.empty());
        });
        assertThat(all.rows()).singleElement().satisfies(row -> {
            assertThat(valueOf(row, "rows")).isEqualTo(Value.of(6L));
            assertThat(valueOf(row, "sum")).isEqualTo(decimal("15"));
        });
    }

    @Test
    void withKeysAGroupExistsOnlyForKeysThatRowsHave() {
        Query query = Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("none")))
                .groupBy(GroupKey.attribute("i", "category"))
                .aggregate(Aggregate.count("rows"))
                .build();

        assertThat(run(query).rows()).isEmpty();
    }

    @Test
    void aGroupingWithoutKeysOrAggregatesGivesOneRowWithoutColumns() {
        Query query = Query.from("Item", "i").aggregate().build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).isEmpty();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().cells()).isEmpty();
    }

    // ------------------------------------------------------------------ groups

    @Test
    void rowsWithoutAValueOfAKeyFormAGroupOfTheirOwnThatComesFirst() {
        Query query = Query.from("Item", "i").groupBy(GroupKey.attribute("i", "born", "born"))
                .aggregate(Aggregate.count("rows")).build();

        QueryResult.Rows result = run(query);

        assertThat(result.rows()).extracting(row -> row.cell("born"), row -> valueOf(row, "rows")).containsExactly(
                tuple(Cell.empty(), Value.of(4L)),
                tuple(new Cell.ValueCell(Value.of(LocalDate.of(2020, 3, 1))), Value.of(1L)),
                tuple(new Cell.ValueCell(Value.of(LocalDate.of(2021, 5, 5))), Value.of(1L)));
    }

    @Test
    void aBindingIsAKeyThroughItsIDAndAppearsAsItsState() {
        Query query = Query.from("Item", "i")
                .follow("i", "Next", "n")
                .groupBy("n")
                .aggregate(Aggregate.count("incoming"))
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("n", "incoming");
        assertThat(result.rows()).extracting(row -> QueryRows.text(row, "n", "label"), row -> valueOf(row, "incoming"))
                .containsExactly(tuple("alpha", Value.of(1L)), tuple("Beta", Value.of(1L)),
                        tuple("gamma", Value.of(2L)), tuple("delta", Value.of(1L)));
        assertThat(result.rows().getFirst().entity("n").orElseThrow().id()).isEqualTo(items.id(I1));
    }

    @Test
    void anEmptyBindingIsAKeyOfItsOwn() {
        Query query = Query.from("Item", "i").optionalFollow("i", "Next", "n").groupBy("n")
                .aggregate(Aggregate.count("rows")).build();

        QueryResult.Rows result = run(query);

        assertThat(result.rows().getFirst().cell("n")).isEqualTo(Cell.empty());
        assertThat(valueOf(result.rows().getFirst(), "rows")).as("i5 and i6 have no next item")
                .isEqualTo(Value.of(2L));
        assertThat(result.rows()).hasSize(5);
    }

    @Test
    void aRelationshipAndADepthCanBeKeys() {
        Query byRelationship = Query.from("Item", "i").follow("i", "Next", "n", "r").groupBy("r")
                .aggregate(Aggregate.count("rows")).build();
        Query byDepth = Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("alpha")))
                .traverse("i", "Next", 0, 5, "t", Optional.empty(), Optional.of("depth"))
                .groupBy("depth").aggregate(Aggregate.count("reached")).build();

        assertThat(run(byRelationship).rows()).hasSize(5);
        assertThat(run(byDepth).rows()).extracting(row -> valueOf(row, "depth"), row -> valueOf(row, "reached"))
                .containsExactly(tuple(Value.of(0), Value.of(1L)), tuple(Value.of(1), Value.of(2L)),
                        tuple(Value.of(2), Value.of(1L)));
    }

    @Test
    void numbersThatAreEqualByValueFormOneGroup() {
        ReferenceStore store = ReferenceStore.with(ItemStore.definition());
        store.commit(Transaction.by("test")
                .create("Item", from(1, 1), values("label", "a", "price", money("1.5")))
                .create("Item", from(1, 1), values("label", "b", "price", money("1.50")))
                .create("Item", from(1, 1), values("label", "c", "price", money("2")))
                .build());
        Query query = Query.from("Item", "i").groupBy(GroupKey.attribute("i", "price", "price"))
                .aggregate(Aggregate.count("rows")).build();

        QueryResult.Rows result = rows(store.view(on(3, 1)).query(query));

        assertThat(result.rows()).extracting(row -> valueOf(row, "rows")).containsExactly(Value.of(2L), Value.of(1L));
    }

    @Test
    void severalKeysFormGroupsOfTheirCombinations() {
        Query query = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"), GroupKey.attribute("i", "active", "active"))
                .aggregate(Aggregate.count("rows")).build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("category", "active", "rows");
        assertThat(result.rows()).extracting(row -> valueOf(row, "category"), row -> valueOf(row, "active"),
                row -> valueOf(row, "rows")).containsExactly(
                        tuple(Value.of("x"), Value.of(false), Value.of(1L)),
                        tuple(Value.of("x"), Value.of(true), Value.of(1L)),
                        tuple(Value.of("y"), null, Value.of(2L)),
                        tuple(Value.of("z"), null, Value.of(2L)));
    }

    // ------------------------------------------------------------------ columns

    @Test
    void withoutColumnsAnUngroupedQueryReturnsItsBindingsInTheOrderTheyWereBound() {
        Query query = Query.from("Item", "i")
                .follow("i", "Next", "n")
                .follow("n", "Next", "m", "r")
                .traverse("m", "Next", 0, 1, "t", Optional.empty(), Optional.of("depth"))
                .follow("t", "Next", "u")
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("i", "n", "m", "r", "t", "depth", "u");
        assertThat(result.rows().getFirst().cells().keySet()).containsExactlyElementsOf(result.columns());
    }

    @Test
    void withoutColumnsAGroupedQueryReturnsItsKeysAndAggregates() {
        Query query = Query.from("Item", "i").follow("i", "Next", "n")
                .groupBy(GroupKey.binding("n", "next"), GroupKey.attribute("i", "category", "from"))
                .aggregate(Aggregate.count("rows"), Aggregate.sum(item("quantity"), "quantities"))
                .build();

        assertThat(run(query).columns()).containsExactly("next", "from", "rows", "quantities");
    }

    @Test
    void namedColumnsChooseRenameAndReorder() {
        Query query = Query.from("Item", "i").follow("i", "Next", "n", "r")
                .select(Column.attribute("n", "label", "to"), Column.binding("i", "item"),
                        Column.attribute("r", "weight", "weight"), Column.attribute("n", "quantity"),
                        Column.attribute("i", "born", "born"))
                .where(Condition.eq(item("label"), Operand.literal("gamma")))
                .limit(5)
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("to", "item", "weight", "n.quantity", "born");
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(valueOf(row, "to")).isEqualTo(Value.of("delta"));
            assertThat(QueryRows.text(row, "item", "label")).isEqualTo("gamma");
            assertThat(valueOf(row, "weight")).isEqualTo(Value.of(3));
            assertThat(row.cell("n.quantity")).as("delta has no quantity").isEqualTo(Cell.empty());
            assertThat(row.cell("born")).isEqualTo(Cell.empty());
        });
    }

    @Test
    void aColumnOfAGroupedQueryChoosesAmongItsKeysAndAggregates() {
        Query query = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.count("rows"))
                .select(Column.aggregate("rows", "n"), Column.key("category", "kind"))
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("n", "kind");
        assertThat(result.rows()).extracting(row -> valueOf(row, "n"), row -> valueOf(row, "kind"))
                .containsExactly(tuple(Value.of(2L), Value.of("x")), tuple(Value.of(2L), Value.of("y")),
                        tuple(Value.of(2L), Value.of("z")));
    }

    @Test
    void theRowAccessorsFailForAColumnThatIsNotThere() {
        Row row = run(Query.from("Item", "i").build()).rows().getFirst();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> row.cell("x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'x'").hasMessageContaining("[i]");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> row.value("i"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EntityCell");
    }

    // ------------------------------------------------------------------ order

    @Test
    void rowsComeInTheOrderOfTheIDsOfTheirBindingsInTheOrderTheyWereBound() {
        Query query = Query.from("Item", "i").follow("i", "Next", "n").build();

        QueryResult.Rows result = run(query);

        assertThat(result.rows()).extracting(row -> QueryRows.text(row, "i", "label"),
                row -> QueryRows.text(row, "n", "label")).containsExactly(tuple("alpha", "Beta"),
                        tuple("alpha", "gamma"), tuple("Beta", "gamma"), tuple("gamma", "delta"),
                        tuple("delta", "alpha"));
    }

    @Test
    void theOrderFollowsTheIDsWhateverTheOrderInWhichTheEntitiesWereCreated() {
        ReferenceStore store = ReferenceStore.with(ItemStore.definition());
        store.commit(Transaction.by("test")
                .create(ReferenceStore.id(3), "Item", from(1, 1), values("label", "created first"))
                .create(ReferenceStore.id(1), "Item", from(1, 1), values("label", "created second"))
                .create(ReferenceStore.id(2), "Item", from(1, 1), values("label", "created third"))
                .build());

        QueryResult.Rows result = rows(store.view(on(3, 1)).query(Query.from("Item", "i").build()));

        assertThat(labels(result)).containsExactly("created second", "created third", "created first");
    }

    @Test
    void theQuerysOrderComesFirstAndMissingValuesComeFirstWhenAscending() {
        Query query = Query.from("Item", "i").orderBy(Order.ascending(Column.attribute("i", "quantity"))).build();

        assertThat(labels(run(query))).containsExactly("delta", FULL_WIDTH_A, EMOJI, "gamma", "alpha", "Beta");
    }

    @Test
    void whenDescendingMissingValuesComeLastAndTiesStillFollowTheIDs() {
        Query query = Query.from("Item", "i").orderBy(Order.descending(Column.attribute("i", "quantity"))).build();

        assertThat(labels(run(query))).containsExactly("Beta", "alpha", "gamma", "delta", FULL_WIDTH_A, EMOJI);
    }

    @Test
    void severalOrdersApplyInTurn() {
        Query query = Query.from("Item", "i")
                .orderBy(Order.descending(Column.attribute("i", "category")),
                        Order.ascending(Column.attribute("i", "label")))
                .build();

        assertThat(labels(run(query))).containsExactly(FULL_WIDTH_A, EMOJI, "delta", "gamma", "Beta", "alpha");
    }

    @Test
    void stringsAreOrderedByCodePointAndBooleansFalseFirst() {
        assertThat(labels(run(Query.from("Item", "i").orderBy(Order.ascending(Column.attribute("i", "label")))
                .build()))).containsExactly("Beta", "alpha", "delta", "gamma", FULL_WIDTH_A, EMOJI);
        assertThat(labels(run(Query.from("Item", "i").orderBy(Order.ascending(Column.attribute("i", "active")))
                .build()))).containsExactly("gamma", "delta", FULL_WIDTH_A, EMOJI, "Beta", "alpha");
        assertThat(labels(run(Query.from("Item", "i").orderBy(Order.descending(Column.attribute("i", "active")))
                .build()))).containsExactly("alpha", "Beta", "gamma", "delta", FULL_WIDTH_A, EMOJI);
    }

    @Test
    void numbersAreOrderedByValueAndDatesAndInstantsInTime() {
        assertThat(labels(run(Query.from("Item", "i").where(Condition.hasValue("i", "price"))
                .orderBy(Order.descending(Column.attribute("i", "price"))).build())))
                .containsExactly("gamma", "Beta", "alpha");
        assertThat(labels(run(Query.from("Item", "i").where(Condition.hasValue("i", "born"))
                .orderBy(Order.descending(Column.attribute("i", "born"))).build()))).containsExactly("Beta", "alpha");
        assertThat(labels(run(Query.from("Item", "i").where(Condition.hasValue("i", "seen"))
                .orderBy(Order.descending(Column.attribute("i", "seen"))).build()))).containsExactly("Beta", "alpha");
    }

    @Test
    void aBindingColumnOrdersByIDAndTheOrderNeedNotBeAColumn() {
        Query query = Query.from("Item", "i")
                .select(Column.attribute("i", "label", "label"))
                .orderBy(Order.descending(Column.binding("i")))
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("label");
        assertThat(QueryRows.values(result, "label")).containsExactly(Value.of(EMOJI), Value.of(FULL_WIDTH_A),
                Value.of("delta"), Value.of("gamma"), Value.of("Beta"), Value.of("alpha"));
    }

    @Test
    void anAttributeOfARelationshipAndADepthCanOrder() {
        Query byWeight = Query.from("Item", "i").follow("i", "Next", "n", "r")
                .orderBy(Order.descending(Column.attribute("r", "weight"))).build();
        Query byDepth = Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("alpha")))
                .traverse("i", "Next", 0, 5, "t", Optional.empty(), Optional.of("depth"))
                .orderBy(Order.descending(Column.depth("depth"))).build();

        assertThat(run(byWeight).rows()).extracting(row -> QueryRows.text(row, "i", "label"))
                .containsExactly("delta", "alpha", "gamma", "Beta", "alpha");
        assertThat(texts(run(byDepth), "t", "label")).containsExactly("delta", "Beta", "gamma", "alpha");
    }

    @Test
    void groupedRowsAreOrderedByTheQuerysOrderThenByTheirKeys() {
        Query query = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.sum(item("quantity"), "quantities"), Aggregate.count("rows"))
                .orderBy(Order.descending(Column.aggregate("quantities")))
                .build();
        Query byRows = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.count("rows"))
                .orderBy(Order.descending(Column.aggregate("rows")))
                .build();
        Query byKey = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.count("rows"))
                .orderBy(Order.descending(Column.key("category")))
                .build();

        assertThat(QueryRows.values(run(query), "category")).containsExactly(Value.of("x"), Value.of("y"),
                Value.of("z"));
        assertThat(QueryRows.values(run(byRows), "category")).as("all groups tie, so by key")
                .containsExactly(Value.of("x"), Value.of("y"), Value.of("z"));
        assertThat(QueryRows.values(run(byKey), "category")).containsExactly(Value.of("z"), Value.of("y"),
                Value.of("x"));
    }

    @Test
    void anAggregateWithoutAValueOrdersFirstWhenAscending() {
        Query query = Query.from("Item", "i")
                .groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.max(item("price"), "dearest"))
                .orderBy(Order.ascending(Column.aggregate("dearest")))
                .build();

        assertThat(QueryRows.values(run(query), "category")).containsExactly(Value.of("z"), Value.of("x"),
                Value.of("y"));
    }

    // ------------------------------------------------------------------ limit

    @Test
    void aLimitKeepsTheFirstRowsAfterTheOrder() {
        Query ordered = Query.from("Item", "i").orderBy(Order.descending(Column.attribute("i", "quantity"))).limit(2)
                .build();

        assertThat(labels(run(ordered))).containsExactly("Beta", "alpha");
        assertThat(labels(run(Query.from("Item", "i").limit(2).build()))).containsExactly("alpha", "Beta");
        assertThat(run(Query.from("Item", "i").limit(0).build()).rows()).isEmpty();
        assertThat(run(Query.from("Item", "i").limit(100).build()).rows()).hasSize(6);
    }

    @Test
    void aLimitAppliesToTheGroups() {
        Query query = Query.from("Item", "i").groupBy(GroupKey.attribute("i", "category", "category"))
                .aggregate(Aggregate.count("rows")).orderBy(Order.descending(Column.key("category"))).limit(1).build();

        assertThat(QueryRows.values(run(query), "category")).containsExactly(Value.of("z"));
    }

    // ------------------------------------------------------------------ coordinates

    @Test
    void aResultReportsTheCoordinatesItWasReadAt() {
        QueryResult.Rows result = run(Query.from("Item", "i").build());

        assertThat(result.effectiveAt()).isEqualTo(on(3, 1));
        assertThat(result.basis()).isEqualTo(items.reference.store.latestBasis());
    }

    @Test
    void theSameQueryRunsAgainAtOtherCoordinates() {
        Query query = Query.from("Item", "i").where(Condition.ge(item("quantity"), Operand.literal(5))).build();
        Basis before = items.reference.store.latestBasis();
        items.reference.recordedOn(on(2, 1));
        items.reference.commit(Transaction.by("test").patch(items.id(I3), from(1, 1), values("quantity", 9)).build());

        QueryResult.Rows known = rows(items.reference.store.view(Coordinates.effectiveAt(on(3, 1)).knownAt(before))
                .query(query));
        QueryResult.Rows now = run(query);

        assertThat(labels(known)).containsExactly("alpha", "Beta");
        assertThat(labels(now)).containsExactly("alpha", "Beta", "gamma");
        assertThat(known.basis()).isEqualTo(before);
        assertThat(now.basis()).isEqualTo(items.reference.store.latestBasis()).isNotEqualTo(before);
    }
}
