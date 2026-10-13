package io.github.enricoag1982.cairn.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The records of a query (design D1 of {@code add-queries-and-aggregation}): the builder, the factories, null checks and
 * copies, and rows and cells.
 */
class QueryRecordsTest {

    private static final Instant NOW = Instant.parse("2026-03-31T00:00:00Z");

    private static EntityState entity(String id) {
        return new EntityState(new EntityId(id), "Account", Map.of("number", Value.of("A-1")),
                EffectivePeriod.from(NOW), NOW, new Basis(1));
    }

    private static RelationshipState relationship(String id) {
        return new RelationshipState(new RelationshipId(id), "HeldBy", new EntityId("a"), new EntityId("b"), Map.of(),
                EffectivePeriod.from(NOW), NOW, new Basis(1));
    }

    // ------------------------------------------------------------------ the builder

    @Test
    void theBuilderStartsFromAnEntityTypeAndBindsIt() {
        Query query = Query.from("Account", "a").build();

        assertThat(query.start()).isEqualTo(new Start("Account", "a", false));
        assertThat(query.steps()).isEmpty();
        assertThat(query.grouping()).isEmpty();
        assertThat(query.columns()).isEmpty();
        assertThat(query.order()).isEmpty();
        assertThat(query.limit()).isEmpty();
    }

    @Test
    void theBuilderBuildsEveryKindOfStepInOrder() {
        Condition condition = Condition.hasValue("a", "balance");

        Query query = Query.from("Account", "a").exactType()
                .where(condition)
                .follow("a", "HeldBy", "holder")
                .follow("a", "HeldBy", "other", "held")
                .optionalFollow("a", "HeldBy", "maybe")
                .optionalFollow("a", "HeldBy", "perhaps", "link")
                .traverse("holder", "ownedBy", 0, 5, "party")
                .traverse("holder", "ownedBy", 1, 2, "owner", Optional.of("via"), Optional.of("depth"))
                .step(new QueryStep.Filter(condition))
                .build();

        assertThat(query.start().exactType()).isTrue();
        assertThat(query.steps()).containsExactly(
                new QueryStep.Filter(condition),
                new QueryStep.Follow("a", "HeldBy", "holder", Optional.empty(), false),
                new QueryStep.Follow("a", "HeldBy", "other", Optional.of("held"), false),
                new QueryStep.Follow("a", "HeldBy", "maybe", Optional.empty(), true),
                new QueryStep.Follow("a", "HeldBy", "perhaps", Optional.of("link"), true),
                new QueryStep.Traverse("holder", "ownedBy", "party", Optional.empty(), Optional.empty(), 0, 5),
                new QueryStep.Traverse("holder", "ownedBy", "owner", Optional.of("via"), Optional.of("depth"), 1, 2),
                new QueryStep.Filter(condition));
    }

    @Test
    void theBuilderBuildsGroupingColumnsOrderAndLimit() {
        Aggregate sum = Aggregate.sum(Operand.attribute("a", "balance"), "total");

        Query query = Query.from("Account", "a")
                .groupBy("holder")
                .groupBy(GroupKey.attribute("a", "currency", "currency"))
                .aggregate(sum)
                .aggregate(Aggregate.count("rows"))
                .select(Column.key("holder"), Column.aggregate("total"))
                .select(Column.key("currency"))
                .orderBy(Order.descending(Column.aggregate("total")))
                .orderBy(Order.ascending(Column.key("holder")))
                .limit(3)
                .build();

        assertThat(query.grouping()).contains(new Grouping(
                List.of(new GroupKey("holder", Optional.empty(), "holder"),
                        new GroupKey("a", Optional.of("currency"), "currency")),
                List.of(sum, new Aggregate(Aggregate.Function.COUNT, Optional.empty(), "rows"))));
        assertThat(query.columns()).containsExactly(new Column.KeyColumn("holder", "holder"),
                new Column.AggregateColumn("total", "total"), new Column.KeyColumn("currency", "currency"));
        assertThat(query.order()).containsExactly(
                new Order(new Column.AggregateColumn("total", "total"), true),
                new Order(new Column.KeyColumn("holder", "holder"), false));
        assertThat(query.limit()).isEqualTo(OptionalInt.of(3));
    }

    @Test
    void aggregatesWithoutKeysAreAGroupingWithoutKeys() {
        Query query = Query.from("Account", "a").aggregate(Aggregate.count("rows")).build();

        assertThat(query.grouping()).hasValueSatisfying(grouping -> {
            assertThat(grouping.keys()).isEmpty();
            assertThat(grouping.aggregates()).hasSize(1);
        });
    }

    @Test
    void aBuilderCanBeBuiltMoreThanOnceAndTheQueryIsAValue() {
        Query.Builder builder = Query.from("Account", "a").where(Condition.hasValue("a", "balance"));

        Query first = builder.build();
        builder.limit(1);
        Query second = builder.build();

        assertThat(first.limit()).isEmpty();
        assertThat(second.limit()).isEqualTo(OptionalInt.of(1));
        assertThat(Query.from("Account", "a").where(Condition.hasValue("a", "balance")).build()).isEqualTo(first)
                .hasSameHashCodeAs(first);
    }

    @Test
    void theBuilderRejectsWhatCannotBeAQuery() {
        assertThatThrownBy(() -> Query.from(null, "a")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Query.from("Account", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Query.from("Account", "a").where(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Query.from("Account", "a").follow("a", "HeldBy", "h", (String) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Query.from("Account", "a").limit(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> Query.from("Account", "a").traverse("a", "HeldBy", -1, 2, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Query.from("Account", "a").traverse("a", "HeldBy", 3, 2, "t"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3");
    }

    @Test
    void aQueryCopiesItsLists() {
        List<QueryStep> steps = new ArrayList<>();
        steps.add(new QueryStep.Filter(Condition.hasValue("a", "balance")));
        List<Column> columns = new ArrayList<>(List.of(Column.binding("a")));
        List<Order> order = new ArrayList<>(List.of(Order.ascending(Column.binding("a"))));

        Query query = new Query(new Start("Account", "a", false), steps, Optional.empty(), columns, order,
                OptionalInt.empty());
        steps.clear();
        columns.clear();
        order.clear();

        assertThat(query.steps()).hasSize(1);
        assertThat(query.columns()).hasSize(1);
        assertThat(query.order()).hasSize(1);
        assertThatThrownBy(() -> query.steps().add(new QueryStep.Filter(Condition.and())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aQueryRejectsNullsAndANegativeLimit() {
        Start start = new Start("Account", "a", false);
        assertThatThrownBy(() -> new Query(null, List.of(), Optional.empty(), List.of(), List.of(),
                OptionalInt.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Query(start, null, Optional.empty(), List.of(), List.of(), OptionalInt.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Query(start, List.of(), null, List.of(), List.of(), OptionalInt.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Query(start, List.of(), Optional.empty(), null, List.of(), OptionalInt.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Query(start, List.of(), Optional.empty(), List.of(), null, OptionalInt.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Query(start, List.of(), Optional.empty(), List.of(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Query(start, List.of(), Optional.empty(), List.of(), List.of(),
                OptionalInt.of(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(start, Arrays.asList((QueryStep) null), Optional.empty(), List.of(),
                List.of(), OptionalInt.empty())).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ the records

    @Test
    void theRecordsOfTheStartAndOfStepsRejectNulls() {
        assertThatThrownBy(() -> new Start(null, "a", false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Start("Account", null, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Filter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Follow(null, "n", "b", Optional.empty(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Follow("a", null, "b", Optional.empty(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Follow("a", "n", null, Optional.empty(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Follow("a", "n", "b", null, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Traverse("a", "n", "b", Optional.empty(), null, 0, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryStep.Traverse("a", "n", "b", null, Optional.empty(), 0, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRecordsOfConditionsAndOperandsRejectNulls() {
        Operand operand = Operand.id("a");
        assertThatThrownBy(() -> new Condition.Compare(null, Comparison.EQ, operand))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.Compare(operand, null, operand)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.Compare(operand, Comparison.EQ, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.In(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.In(operand, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.HasValue(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.HasValue("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.HasLink(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.HasLink("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.IsA(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.IsA("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.And(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.Or(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Condition.Not(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Condition.and(Arrays.asList(Condition.and(), null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Operand.AttributeOf(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Operand.AttributeOf("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Operand.IdOf(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Operand.TypeOf(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Operand.Literal(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRecordsOfGroupingColumnsAndOrderRejectNulls() {
        assertThatThrownBy(() -> new GroupKey(null, Optional.empty(), "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GroupKey("a", null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GroupKey("a", Optional.empty(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Aggregate(null, Optional.empty(), "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Aggregate(Aggregate.Function.COUNT, null, "n"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Aggregate(Aggregate.Function.COUNT, Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Aggregate.sum(null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Grouping(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Grouping(List.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Column.BindingColumn(null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Column.BindingColumn("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Column.AttributeColumn("a", null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Column.DepthColumn(null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Column.KeyColumn(null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Column.AggregateColumn("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order(null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theFactoriesBuildTheRecords() {
        Operand left = Operand.attribute("a", "balance");

        assertThat(Operand.id("a")).isEqualTo(new Operand.IdOf("a"));
        assertThat(Operand.type("a")).isEqualTo(new Operand.TypeOf("a"));
        assertThat(Operand.literal(Value.of(1))).isEqualTo(new Operand.Literal(Value.of(1)));
        assertThat(Operand.literal("x")).isEqualTo(new Operand.Literal(Value.of("x")));
        assertThat(Operand.literal(true)).isEqualTo(new Operand.Literal(Value.of(true)));
        assertThat(Operand.literal(1)).isEqualTo(new Operand.Literal(Value.of(1)));
        assertThat(Operand.literal(1L)).isEqualTo(new Operand.Literal(Value.of(1L)));
        assertThat(Operand.literal(BigDecimal.ONE)).isEqualTo(new Operand.Literal(Value.of(BigDecimal.ONE)));
        assertThat(Operand.literal(LocalDate.of(2026, 1, 1)))
                .isEqualTo(new Operand.Literal(Value.of(LocalDate.of(2026, 1, 1))));
        assertThat(Operand.literal(NOW)).isEqualTo(new Operand.Literal(Value.of(NOW)));
        for (Comparison comparison : Comparison.values()) {
            assertThat(Condition.compare(left, comparison, Operand.literal(1)))
                    .isEqualTo(new Condition.Compare(left, comparison, Operand.literal(1)));
        }
        assertThat(Condition.eq(left, Operand.literal(1))).isEqualTo(Condition.compare(left, Comparison.EQ,
                Operand.literal(1)));
        assertThat(Condition.ne(left, Operand.literal(1))).isEqualTo(Condition.compare(left, Comparison.NE,
                Operand.literal(1)));
        assertThat(Condition.lt(left, Operand.literal(1))).isEqualTo(Condition.compare(left, Comparison.LT,
                Operand.literal(1)));
        assertThat(Condition.le(left, Operand.literal(1))).isEqualTo(Condition.compare(left, Comparison.LE,
                Operand.literal(1)));
        assertThat(Condition.gt(left, Operand.literal(1))).isEqualTo(Condition.compare(left, Comparison.GT,
                Operand.literal(1)));
        assertThat(Condition.ge(left, Operand.literal(1))).isEqualTo(Condition.compare(left, Comparison.GE,
                Operand.literal(1)));
        assertThat(Condition.in(left, Value.of(1), Value.of(2))).isEqualTo(new Condition.In(left,
                List.of(Value.of(1), Value.of(2))));
        assertThat(Condition.in(left, List.of(Value.of(1)))).isEqualTo(new Condition.In(left, List.of(Value.of(1))));
        assertThat(Condition.hasValue("a", "x")).isEqualTo(new Condition.HasValue("a", "x"));
        assertThat(Condition.hasLink("a", "x")).isEqualTo(new Condition.HasLink("a", "x"));
        assertThat(Condition.isA("a", "x")).isEqualTo(new Condition.IsA("a", "x"));
        Condition one = Condition.hasValue("a", "x");
        assertThat(Condition.and(one, one)).isEqualTo(new Condition.And(List.of(one, one)));
        assertThat(Condition.and(List.of(one))).isEqualTo(new Condition.And(List.of(one)));
        assertThat(Condition.or(one, one)).isEqualTo(new Condition.Or(List.of(one, one)));
        assertThat(Condition.or(List.of(one))).isEqualTo(new Condition.Or(List.of(one)));
        assertThat(Condition.not(one)).isEqualTo(new Condition.Not(one));
    }

    @Test
    void columnsKeysAndAggregatesHaveDefaultNames() {
        assertThat(Column.binding("a")).isEqualTo(new Column.BindingColumn("a", "a"));
        assertThat(Column.binding("a", "x")).isEqualTo(new Column.BindingColumn("a", "x"));
        assertThat(Column.attribute("a", "balance")).isEqualTo(new Column.AttributeColumn("a", "balance", "a.balance"));
        assertThat(Column.attribute("a", "balance", "x")).isEqualTo(new Column.AttributeColumn("a", "balance", "x"));
        assertThat(Column.depth("d")).isEqualTo(new Column.DepthColumn("d", "d"));
        assertThat(Column.depth("d", "x")).isEqualTo(new Column.DepthColumn("d", "x"));
        assertThat(Column.key("k")).isEqualTo(new Column.KeyColumn("k", "k"));
        assertThat(Column.key("k", "x")).isEqualTo(new Column.KeyColumn("k", "x"));
        assertThat(Column.aggregate("t")).isEqualTo(new Column.AggregateColumn("t", "t"));
        assertThat(Column.aggregate("t", "x")).isEqualTo(new Column.AggregateColumn("t", "x"));
        assertThat(Column.attribute("a", "balance").name()).isEqualTo("a.balance");
        assertThat(GroupKey.binding("a")).isEqualTo(new GroupKey("a", Optional.empty(), "a"));
        assertThat(GroupKey.binding("a", "x")).isEqualTo(new GroupKey("a", Optional.empty(), "x"));
        assertThat(GroupKey.attribute("a", "balance"))
                .isEqualTo(new GroupKey("a", Optional.of("balance"), "a.balance"));
        assertThat(GroupKey.attribute("a", "balance", "x")).isEqualTo(new GroupKey("a", Optional.of("balance"), "x"));
        assertThat(Order.ascending(Column.binding("a"))).isEqualTo(new Order(Column.binding("a"), false));
        assertThat(Order.descending(Column.binding("a"))).isEqualTo(new Order(Column.binding("a"), true));
        Operand.AttributeOf of = Operand.attribute("a", "balance");
        assertThat(Aggregate.count("n")).isEqualTo(new Aggregate(Aggregate.Function.COUNT, Optional.empty(), "n"));
        assertThat(Aggregate.count(of, "n")).isEqualTo(new Aggregate(Aggregate.Function.COUNT, Optional.of(of), "n"));
        assertThat(Aggregate.sum(of, "n")).isEqualTo(new Aggregate(Aggregate.Function.SUM, Optional.of(of), "n"));
        assertThat(Aggregate.min(of, "n")).isEqualTo(new Aggregate(Aggregate.Function.MIN, Optional.of(of), "n"));
        assertThat(Aggregate.max(of, "n")).isEqualTo(new Aggregate(Aggregate.Function.MAX, Optional.of(of), "n"));
        assertThat(Aggregate.avg(of, "n")).isEqualTo(new Aggregate(Aggregate.Function.AVG, Optional.of(of), "n"));
    }

    @Test
    void onlyOrderComparisonsNeedAnOrder() {
        assertThat(Comparison.values()).filteredOn(Comparison::needsOrder)
                .containsExactly(Comparison.LT, Comparison.LE, Comparison.GT, Comparison.GE);
    }

    @Test
    void listsOfConditionsValuesAndKeysAreCopied() {
        List<Value> values = new ArrayList<>(List.of(Value.of(1)));
        List<Condition> conditions = new ArrayList<>(List.of(Condition.hasValue("a", "x")));
        List<GroupKey> keys = new ArrayList<>(List.of(GroupKey.binding("a")));
        List<Aggregate> aggregates = new ArrayList<>(List.of(Aggregate.count("n")));

        Condition.In in = new Condition.In(Operand.id("a"), values);
        Condition.And and = new Condition.And(conditions);
        Grouping grouping = new Grouping(keys, aggregates);
        values.clear();
        conditions.clear();
        keys.clear();
        aggregates.clear();

        assertThat(in.values()).hasSize(1);
        assertThat(and.conditions()).hasSize(1);
        assertThat(grouping.keys()).hasSize(1);
        assertThat(grouping.aggregates()).hasSize(1);
    }

    // ------------------------------------------------------------------ results

    @Test
    void aRowReadsItsCellsByColumnName() {
        Row row = new Row(Map.of("a", new Cell.EntityCell(entity("1"))));
        Map<String, Cell> cells = new LinkedHashMap<>();
        cells.put("account", new Cell.EntityCell(entity("1")));
        cells.put("held", new Cell.RelationshipCell(relationship("2")));
        cells.put("total", new Cell.ValueCell(Value.of(5L)));
        cells.put("nobody", Cell.empty());

        Row full = new Row(cells);
        cells.clear();

        assertThat(row.entity("a")).hasValueSatisfying(state -> assertThat(state.id()).isEqualTo(new EntityId("1")));
        assertThat(full.cells().keySet()).as("in the order of the columns").containsExactly("account", "held", "total",
                "nobody");
        assertThat(full.entity("account")).isPresent();
        assertThat(full.relationship("held")).hasValueSatisfying(state ->
                assertThat(state.id()).isEqualTo(new RelationshipId("2")));
        assertThat(full.value("total")).contains(Value.of(5L));
        assertThat(full.cell("total")).isEqualTo(new Cell.ValueCell(Value.of(5L)));
        assertThat(full.entity("nobody")).isEmpty();
        assertThat(full.relationship("nobody")).isEmpty();
        assertThat(full.value("nobody")).isEmpty();
        assertThatThrownBy(() -> full.cells().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRowFailsForAColumnItDoesNotHaveOrOfAnotherKind() {
        Row row = new Row(Map.of("account", new Cell.EntityCell(entity("1")), "total", new Cell.ValueCell(Value.of(5L)),
                "held", new Cell.RelationshipCell(relationship("2"))));

        assertThatThrownBy(() -> row.cell("other")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'other'");
        assertThatThrownBy(() -> row.entity("total")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ValueCell").hasMessageContaining("an entity");
        assertThatThrownBy(() -> row.relationship("account")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a relationship");
        assertThatThrownBy(() -> row.value("held")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a value");
        assertThatThrownBy(() -> new Row(null)).isInstanceOf(NullPointerException.class);
        Map<String, Cell> withNull = new LinkedHashMap<>();
        withNull.put("x", null);
        assertThatThrownBy(() -> new Row(withNull)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cellsRejectNulls() {
        assertThatThrownBy(() -> new Cell.EntityCell(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Cell.RelationshipCell(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Cell.ValueCell(null)).isInstanceOf(NullPointerException.class);
        assertThat(Cell.empty()).isEqualTo(new Cell.Empty());
    }

    @Test
    void resultsCopyTheirListsAndRejectNulls() {
        List<String> columns = new ArrayList<>(List.of("a"));
        List<Row> rows = new ArrayList<>(List.of(new Row(Map.of("a", Cell.empty()))));
        List<Problem> problems = new ArrayList<>(List.of(new Problem(ProblemCode.UNKNOWN_BINDING, "@query.steps.0",
                "No such binding.")));

        QueryResult.Rows result = new QueryResult.Rows(NOW, new Basis(3), columns, rows);
        QueryResult.Invalid invalid = new QueryResult.Invalid(problems);
        columns.clear();
        rows.clear();
        problems.clear();

        assertThat(result.columns()).containsExactly("a");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.effectiveAt()).isEqualTo(NOW);
        assertThat(result.basis()).isEqualTo(new Basis(3));
        assertThat(invalid.problems()).hasSize(1);
        assertThatThrownBy(() -> new QueryResult.Rows(null, new Basis(3), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryResult.Rows(NOW, null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryResult.Rows(NOW, new Basis(3), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryResult.Rows(NOW, new Basis(3), List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryResult.Invalid(null)).isInstanceOf(NullPointerException.class);
    }
}
