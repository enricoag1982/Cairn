package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ItemStore.EMOJI;
import static io.github.enricoag1982.cairn.core.ItemStore.FULL_WIDTH_A;
import static io.github.enricoag1982.cairn.core.ItemStore.I1;
import static io.github.enricoag1982.cairn.core.ItemStore.I2;
import static io.github.enricoag1982.cairn.core.ItemStore.I3;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.QueryRows.texts;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the steps of a query mean (design D2 of {@code add-queries-and-aggregation}): the start, comparisons of every
 * value type, tests for values, links and types, follow steps, and traversals with their depths, over the items of
 * {@link ItemStore}. Grouping, order and limits are in {@link QueryResultsTest}.
 */
class QueryEvaluationTest {

    private final ItemStore items = new ItemStore();

    private static Operand.AttributeOf item(String attribute) {
        return Operand.attribute("i", attribute);
    }

    private QueryResult.Rows run(Query query) {
        return rows(items.view().query(query));
    }

    /** The labels of the items of a condition, in the order of the rows. */
    private List<String> labelsWhere(Condition condition) {
        return texts(run(Query.from("Item", "i").where(condition).build()), "i", "label");
    }

    private static Value date(int year, int month, int day) {
        return Value.of(LocalDate.of(year, month, day));
    }

    // ------------------------------------------------------------------ the start

    @Test
    void theStartTakesTheSubtypesUnlessItAsksForTheExactType() {
        assertThat(texts(run(Query.from("Item", "i").build()), "i", "label"))
                .containsExactly("alpha", "Beta", "gamma", "delta", FULL_WIDTH_A, EMOJI);
        assertThat(texts(run(Query.from("Item", "i").exactType().build()), "i", "label"))
                .containsExactly("alpha", "Beta", "delta", FULL_WIDTH_A, EMOJI);
        assertThat(texts(run(Query.from("Gadget", "g").exactType().build()), "g", "label")).containsExactly("gamma");
        assertThat(run(Query.from("Thing", "t").build()).rows()).hasSize(6);
        assertThat(run(Query.from("Thing", "t").exactType().build()).rows()).as("abstract").isEmpty();
    }

    @Test
    void theStartTypeResolvesByAliasWithoutRegardToCase() {
        assertThat(run(Query.from("ARTICLE", "i").build()).rows()).hasSize(6);
        assertThat(run(Query.from("gadget", "i").build()).rows()).hasSize(1);
    }

    @Test
    void theStartTakesOnlyTheEntitiesEffectiveAtTheViewsTime() {
        Query query = Query.from("Item", "i").build();

        assertThat(items.reference.view(on(2025, 12, 31))
                .query(query)).isInstanceOfSatisfying(QueryResult.Rows.class, result ->
                assertThat(result.rows()).isEmpty());
    }

    // ------------------------------------------------------------------ comparisons

    @Test
    void numbersCompareByValueWhateverTheirValueTypes() {
        assertThat(labelsWhere(Condition.gt(item("quantity"), Operand.literal(4L)))).as("int with a long")
                .containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.ge(item("quantity"), Operand.literal(new BigDecimal("5.0")))))
                .as("int with a decimal").containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.eq(item("quantity"), Operand.literal(5)))).containsExactly("alpha");
        assertThat(labelsWhere(Condition.eq(item("serial"), Operand.literal(7)))).as("long with an int")
                .containsExactly("Beta");
        assertThat(labelsWhere(Condition.gt(item("serial"), Operand.literal(Integer.MAX_VALUE))))
                .containsExactly("alpha");
        assertThat(labelsWhere(Condition.gt(item("price"), Operand.literal(2)))).as("decimal with an int")
                .containsExactly("Beta", "gamma");
        assertThat(labelsWhere(Condition.eq(item("price"), Operand.literal(new BigDecimal("1.5")))))
                .as("1.50 equals 1.5").containsExactly("alpha");
        assertThat(labelsWhere(Condition.eq(item("price"), Operand.literal(10)))).containsExactly("gamma");
        assertThat(labelsWhere(Condition.lt(item("quantity"), item("serial")))).as("attribute with attribute")
                .containsExactly("alpha");
        assertThat(labelsWhere(Condition.le(item("quantity"), item("serial")))).containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.ne(item("quantity"), Operand.literal(5L)))).containsExactly("Beta", "gamma");
        assertThat(labelsWhere(Condition.lt(Operand.literal(4), item("quantity")))).as("literal first")
                .containsExactly("alpha", "Beta");
    }

    @Test
    void stringsCompareByCodePointAndCaseMatters() {
        assertThat(labelsWhere(Condition.eq(item("label"), Operand.literal("alpha")))).containsExactly("alpha");
        assertThat(labelsWhere(Condition.eq(item("label"), Operand.literal("Alpha")))).isEmpty();
        assertThat(labelsWhere(Condition.lt(item("label"), Operand.literal("a")))).as("B comes before a")
                .containsExactly("Beta");
        assertThat(labelsWhere(Condition.lt(item("label"), Operand.literal(EMOJI))))
                .as("U+FF21 is below U+1F600, though its UTF-16 unit is above the surrogates")
                .containsExactly("alpha", "Beta", "gamma", "delta", FULL_WIDTH_A);
        assertThat(labelsWhere(Condition.gt(item("label"), Operand.literal(FULL_WIDTH_A)))).containsExactly(EMOJI);
    }

    @Test
    void datesAndInstantsCompareInTheirOrder() {
        assertThat(labelsWhere(Condition.ge(item("born"), Operand.literal(LocalDate.of(2021, 1, 1)))))
                .containsExactly("Beta");
        assertThat(labelsWhere(Condition.lt(item("born"), Operand.literal(LocalDate.of(2021, 1, 1)))))
                .containsExactly("alpha");
        assertThat(labelsWhere(Condition.eq(item("seen"), Operand.literal(Instant.parse("2026-02-01T10:00:00Z")))))
                .containsExactly("alpha");
        assertThat(labelsWhere(Condition.gt(item("seen"), Operand.literal(Instant.parse("2026-02-01T10:00:00Z")))))
                .containsExactly("Beta");
    }

    @Test
    void aLiteralTakesTheValueTypeOfADateOrInstantAttributeWhereItCan() {
        assertThat(labelsWhere(Condition.gt(item("born"), Operand.literal("2020-12-31")))).containsExactly("Beta");
        assertThat(labelsWhere(Condition.eq(item("born"), Operand.literal("2020-03-01")))).containsExactly("alpha");
        assertThat(labelsWhere(Condition.eq(item("seen"), Operand.literal("2026-02-01T11:00:00+01:00"))))
                .as("an offset is converted to UTC").containsExactly("alpha");
        assertThat(labelsWhere(Condition.lt(item("seen"), Operand.literal("2026-02-15T00:00:00Z"))))
                .containsExactly("alpha");
        assertThat(labelsWhere(Condition.lt(Operand.literal("2026-02-15T00:00:00Z"), item("seen"))))
                .containsExactly("Beta");
    }

    @Test
    void booleansCompareForEquality() {
        assertThat(labelsWhere(Condition.eq(item("active"), Operand.literal(true)))).containsExactly("alpha");
        assertThat(labelsWhere(Condition.eq(item("active"), Operand.literal(false)))).containsExactly("Beta");
        assertThat(labelsWhere(Condition.ne(item("active"), Operand.literal(true)))).containsExactly("Beta");
    }

    @Test
    void aComparisonWithAMissingValueIsFalseWhateverTheComparison() {
        // gamma has no active flag, delta no quantity
        assertThat(labelsWhere(Condition.ne(item("quantity"), Operand.literal(5)))).containsExactly("Beta", "gamma");
        assertThat(labelsWhere(Condition.lt(item("quantity"), Operand.literal(100)))).containsExactly("alpha", "Beta",
                "gamma");
        assertThat(labelsWhere(Condition.ne(item("category"), Operand.literal("x")))).containsExactly("gamma", "delta",
                FULL_WIDTH_A, EMOJI);
        assertThat(labelsWhere(Condition.not(Condition.eq(item("quantity"), Operand.literal(5)))))
                .as("not turns a false comparison true").containsExactly("Beta", "gamma", "delta", FULL_WIDTH_A, EMOJI);
        assertThat(labelsWhere(Condition.hasValue("i", "quantity"))).containsExactly("alpha", "Beta", "gamma");
        assertThat(labelsWhere(Condition.not(Condition.hasValue("i", "quantity"))))
                .containsExactly("delta", FULL_WIDTH_A, EMOJI);
    }

    @Test
    void anInTestComparesWithEachValue() {
        assertThat(labelsWhere(Condition.in(item("quantity"), Value.of(2), Value.of(3), Value.of(5L),
                Value.of(new BigDecimal("7.0"))))).containsExactly("alpha", "Beta", "gamma");
        assertThat(labelsWhere(Condition.in(item("category"), Value.of("x")))).containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.in(item("category"), List.of()))).isEmpty();
        assertThat(labelsWhere(Condition.in(item("born"), Value.of("2020-03-01"), date(2021, 5, 5))))
                .containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.in(Operand.literal(5), Value.of(1), Value.of(5L)))).hasSize(6);
    }

    @Test
    void idsAndTypesCompareAsStrings() {
        EntityId gamma = items.id(I3);

        assertThat(labelsWhere(Condition.eq(Operand.id("i"), Operand.literal(gamma.value())))).containsExactly("gamma");
        assertThat(labelsWhere(Condition.eq(Operand.type("i"), Operand.literal("Gadget")))).containsExactly("gamma");
        assertThat(labelsWhere(Condition.ne(Operand.type("i"), Operand.literal("Item"))))
                .as("the exact type of gamma is Gadget").containsExactly("gamma");
        assertThat(labelsWhere(Condition.lt(Operand.id("i"), Operand.literal(gamma.value()))))
                .containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.eq(Operand.type("i"), Operand.literal("gadget")))).as("exact text").isEmpty();
    }

    @Test
    void isAIncludesSubtypes() {
        assertThat(labelsWhere(Condition.isA("i", "Item"))).hasSize(6);
        assertThat(labelsWhere(Condition.isA("i", "Thing"))).hasSize(6);
        assertThat(labelsWhere(Condition.isA("i", "gadget"))).containsExactly("gamma");
        assertThat(labelsWhere(Condition.isA("i", "Article"))).as("by alias").hasSize(6);
    }

    @Test
    void conditionsCombine() {
        Condition cheap = Condition.lt(item("price"), Operand.literal(5));
        Condition inX = Condition.eq(item("category"), Operand.literal("x"));

        assertThat(labelsWhere(Condition.and(cheap, inX))).containsExactly("alpha", "Beta");
        assertThat(labelsWhere(Condition.and(cheap, Condition.not(inX)))).isEmpty();
        assertThat(labelsWhere(Condition.or(Condition.eq(item("label"), Operand.literal("delta")), cheap)))
                .containsExactly("alpha", "Beta", "delta");
        assertThat(labelsWhere(Condition.and())).as("no conditions all hold").hasSize(6);
        assertThat(labelsWhere(Condition.or())).as("no condition holds").isEmpty();
        assertThat(run(Query.from("Item", "i").where(cheap).where(inX).where(Condition.hasValue("i", "born")).build())
                .rows()).as("steps apply one after the other").hasSize(2);
    }

    @Test
    void hasLinkTestsForLinksByARelationshipNameOrInverseName() {
        assertThat(labelsWhere(Condition.hasLink("i", "Next"))).containsExactly("alpha", "Beta", "gamma", "delta");
        assertThat(labelsWhere(Condition.hasLink("i", "previous"))).containsExactly("alpha", "Beta", "gamma", "delta");
        assertThat(labelsWhere(Condition.not(Condition.hasLink("i", "Next")))).containsExactly(FULL_WIDTH_A, EMOJI);
    }

    private static Condition weightAbove(String relationship, int weight) {
        return Condition.gt(Operand.attribute(relationship, "weight"), Operand.literal(weight));
    }

    @Test
    void hasLinkWithAConditionCountsOnlyTheLinksWhoseRelationshipMeetsIt() {
        // Next: alpha to Beta (1), Beta to gamma (2), gamma to delta (3), alpha to gamma (4), delta to alpha (5)
        assertThat(labelsWhere(Condition.hasLink("i", "Next", "n", weightAbove("n", 3))))
                .as("an item with a link that leaves it by a weight above 3").containsExactly("alpha", "delta");
        assertThat(labelsWhere(Condition.hasLink("i", "previous", "n", weightAbove("n", 3))))
                .as("by the inverse name, the links that lead to it").containsExactly("alpha", "gamma");
        assertThat(labelsWhere(Condition.hasLink("i", "Before", "n", weightAbove("n", 4))))
                .as("by an alias of the inverse name").containsExactly("alpha");
        assertThat(labelsWhere(Condition.not(Condition.hasLink("i", "Next", "n", weightAbove("n", 3)))))
                .as("an item that has no such link").containsExactly("Beta", "gamma", FULL_WIDTH_A, EMOJI);
        assertThat(labelsWhere(Condition.hasLink("i", "Next", "n", weightAbove("n", 5)))).isEmpty();
    }

    @Test
    void aLinkIsCountedIfOneOfSeveralMeetsTheConditionNotIfTheyAllMust() {
        // alpha leaves by weights 1 and 4, and Beta by 2: some link of alpha is above 3, and no link of Beta is
        assertThat(labelsWhere(Condition.hasLink("i", "Next", "n", Condition.and(weightAbove("n", 3),
                Condition.not(weightAbove("n", 4)))))).containsExactly("alpha");
        assertThat(labelsWhere(Condition.hasLink("i", "Next", "n", weightAbove("n", 1))))
                .containsExactly("alpha", "Beta", "gamma", "delta");
    }

    @Test
    void theConditionOfALinkTestCanBeLiteralsOnlyAndThenDependsOnHavingALink() {
        Condition always = Condition.eq(Operand.literal(1), Operand.literal(1));
        Condition never = Condition.eq(Operand.literal(1), Operand.literal(2));

        assertThat(labelsWhere(Condition.hasLink("i", "Next", "n", always)))
                .containsExactly("alpha", "Beta", "gamma", "delta");
        assertThat(labelsWhere(Condition.hasLink("i", "Next", "n", never))).isEmpty();
    }

    @Test
    void theRelationshipNameOfALinkTestBelongsToTheTestAndHidesNothingOutsideIt() {
        // r is an entity of the follow step, and a relationship inside the condition of the link test; so is i
        Query query = Query.from("Item", "i")
                .follow("i", "Next", "r")
                .where(Condition.and(Condition.hasLink("i", "Next", "r", weightAbove("r", 3)),
                        Condition.hasValue("r", "label")))
                .build();
        Query sameAsStart = Query.from("Item", "i")
                .where(Condition.hasLink("i", "Next", "i", weightAbove("i", 3)))
                .where(Condition.hasValue("i", "label"))
                .build();

        QueryResult.Rows result = run(query);

        assertThat(result.rows()).extracting(row -> QueryRows.text(row, "i", "label"),
                row -> QueryRows.text(row, "r", "label"))
                .containsExactly(tuple("alpha", "Beta"), tuple("alpha", "gamma"), tuple("delta", "alpha"));
        assertThat(texts(run(sameAsStart), "i", "label")).containsExactly("alpha", "delta");
    }

    @Test
    void aLinkTestOnlyCountsTheLinksEffectiveAtTheViewsTime() {
        ReferenceStore store = ReferenceStore.with(ItemStore.definition());
        store.commit(Transaction.by("test")
                .create(I1, "Item", from(1, 1), values("label", "one"))
                .create(I2, "Item", from(1, 1), values("label", "two"))
                .relate("Next", I1, I2, from(4, 1), values("weight", 9))
                .build());
        Condition heavy = Condition.hasLink("i", "Next", "n", weightAbove("n", 3));
        Query query = Query.from("Item", "i").where(heavy).build();

        assertThat(rows(store.view(on(3, 1)).query(query)).rows()).as("the link starts in April").isEmpty();
        assertThat(texts(rows(store.view(on(5, 1)).query(query)), "i", "label")).containsExactly("one");
    }

    // ------------------------------------------------------------------ follow

    @Test
    void aFollowStepGivesOneRowForEachRelationship() {
        Query query = Query.from("Item", "i").follow("i", "Next", "n", "r").build();

        QueryResult.Rows result = run(query);

        assertThat(result.columns()).containsExactly("i", "n", "r");
        assertThat(result.rows()).extracting(row -> QueryRows.text(row, "i", "label"),
                row -> QueryRows.text(row, "n", "label"), row -> weight(row, "r"))
                .containsExactly(tuple("alpha", "Beta", 1), tuple("alpha", "gamma", 4), tuple("Beta", "gamma", 2),
                        tuple("gamma", "delta", 3), tuple("delta", "alpha", 5));
    }

    private static int weight(Row row, String column) {
        return ((Value.IntValue) row.relationship(column).orElseThrow().values().get("weight")).value();
    }

    @Test
    void aFollowStepLeadsByInverseNamesAndAliases() {
        for (String name : List.of("previous", "Before", "BEFORE")) {
            assertThat(texts(run(Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("gamma")))
                    .follow("i", name, "p").build()), "p", "label")).as(name).containsExactly("alpha", "Beta");
        }
        assertThat(texts(run(Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("alpha")))
                .follow("i", "Successor", "n").build()), "n", "label")).containsExactly("Beta", "gamma");
    }

    @Test
    void theRelationshipsOfAFollowStepAreTheOnesEffectiveAtTheViewsTime() {
        ReferenceStore store = ReferenceStore.with(ItemStore.definition());
        store.commit(Transaction.by("test")
                .create(I1, "Item", from(1, 1), values("label", "one"))
                .create(I2, "Item", from(1, 1), values("label", "two"))
                .relate("Next", I1, I2, EffectivePeriod.between(on(2, 1), on(3, 1)), values())
                .build());
        Query follow = Query.from("Item", "i").follow("i", "Next", "n").build();
        Query traverse = Query.from("Item", "i").traverse("i", "Next", 1, 3, "n").build();

        for (Query query : List.of(follow, traverse)) {
            assertThat(rows(store.view(on(1, 15)).query(query)).rows()).as("before it starts").isEmpty();
            assertThat(rows(store.view(on(2, 15)).query(query)).rows()).as("while it is effective").hasSize(1);
            assertThat(rows(store.view(on(3, 1)).query(query)).rows()).as("from its end").isEmpty();
        }
    }

    @Test
    void anOptionalFollowKeepsRowsWithoutRelationshipsWithEmptyBindings() {
        Query query = Query.from("Item", "i").optionalFollow("i", "Next", "n", "r").build();

        QueryResult.Rows result = run(query);

        assertThat(result.rows()).hasSize(7);
        Row withoutLinks = result.rows().get(5);
        assertThat(QueryRows.text(withoutLinks, "i", "label")).isEqualTo(FULL_WIDTH_A);
        assertThat(withoutLinks.cell("n")).isEqualTo(Cell.empty());
        assertThat(withoutLinks.cell("r")).isEqualTo(Cell.empty());
        assertThat(withoutLinks.entity("n")).isEmpty();
        assertThat(withoutLinks.relationship("r")).isEmpty();
    }

    @Test
    void aComparisonWithAnEmptyBindingIsFalse() {
        Query.Builder base = Query.from("Item", "i").optionalFollow("i", "previous", "before", "r");
        Condition labelIsDelta = Condition.eq(Operand.attribute("before", "label"), Operand.literal("delta"));

        List<Row> equal = rows(items.view().query(base.where(labelIsDelta).build())).rows();

        assertThat(equal).extracting(row -> QueryRows.text(row, "i", "label")).containsExactly("alpha");
    }

    @Test
    void anEmptyBindingIsFalseForEveryKindOfConditionAndNotTurnsItTrue() {
        // the rows of i5 and i6 have no previous item, so their binding is empty
        List<Condition> conditions = List.of(
                Condition.eq(Operand.attribute("p", "label"), Operand.literal("alpha")),
                Condition.ne(Operand.attribute("p", "label"), Operand.literal("alpha")),
                Condition.in(Operand.attribute("p", "label"), Value.of("alpha")),
                Condition.hasValue("p", "label"),
                Condition.hasLink("p", "Next"),
                Condition.isA("p", "Item"),
                Condition.eq(Operand.id("p"), Operand.literal("1")),
                Condition.eq(Operand.type("p"), Operand.literal("Item")),
                Condition.eq(Operand.attribute("r", "weight"), Operand.literal(1)));
        for (Condition condition : conditions) {
            Query holds = Query.from("Item", "i").where(Condition.isA("i", "Article")).optionalFollow("i", "previous",
                    "p", "r").where(condition).build();
            Query holdsNot = Query.from("Item", "i").optionalFollow("i", "previous", "p", "r")
                    .where(Condition.not(condition)).build();

            assertThat(run(holds).rows()).extracting(row -> QueryRows.text(row, "i", "label"))
                    .as("%s", condition).doesNotContain(FULL_WIDTH_A, EMOJI);
            assertThat(run(holdsNot).rows()).extracting(row -> QueryRows.text(row, "i", "label"))
                    .as("not %s", condition).contains(FULL_WIDTH_A, EMOJI);
        }
    }

    @Test
    void aFollowStepFromAnEmptyBindingKeepsNothingUnlessItIsOptional() {
        Query strict = Query.from("Item", "i").optionalFollow("i", "previous", "p").follow("p", "previous", "pp").build();
        Query optional = Query.from("Item", "i").optionalFollow("i", "previous", "p")
                .optionalFollow("p", "previous", "pp").build();

        assertThat(run(strict).rows()).extracting(row -> QueryRows.text(row, "i", "label"),
                row -> QueryRows.text(row, "p", "label"), row -> QueryRows.text(row, "pp", "label")).containsExactly(
                        tuple("alpha", "delta", "gamma"), tuple("Beta", "alpha", "delta"),
                        tuple("gamma", "alpha", "delta"), tuple("gamma", "Beta", "alpha"),
                        tuple("delta", "gamma", "alpha"), tuple("delta", "gamma", "Beta"));
        assertThat(run(optional).rows()).extracting(row -> QueryRows.text(row, "i", "label"),
                row -> QueryRows.text(row, "p", "label"), row -> QueryRows.text(row, "pp", "label"))
                .contains(tuple(FULL_WIDTH_A, null, null), tuple(EMOJI, null, null), tuple("alpha", "delta", "gamma"));
    }

    @Test
    void theEntityAtTheOtherEndKeepsItsConcreteType() {
        Query query = Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("Beta")))
                .follow("i", "Next", "n").build();

        QueryResult.Rows result = run(query);

        assertThat(result.rows()).singleElement().satisfies(row ->
                assertThat(row.entity("n").orElseThrow().entityType()).isEqualTo("Gadget"));
    }

    // ------------------------------------------------------------------ traversal

    private Query traversalFromAlpha(int minDepth, int maxDepth, String name) {
        return Query.from("Item", "i").where(Condition.eq(item("label"), Operand.literal("alpha")))
                .traverse("i", name, minDepth, maxDepth, "t", Optional.of("via"), Optional.of("depth"))
                .orderBy(Order.ascending(Column.depth("depth")), Order.ascending(Column.binding("t")))
                .build();
    }

    private List<Object> reached(QueryResult.Rows result) {
        return result.rows().stream().<Object>map(row -> tuple(QueryRows.text(row, "t", "label"),
                ((Value.IntValue) row.value("depth").orElseThrow()).value())).toList();
    }

    @Test
    void aTraversalBindsEachEntityOnceAtItsSmallestDepth() {
        QueryResult.Rows result = run(traversalFromAlpha(0, 10, "Next"));

        // gamma is one step from alpha, and two through Beta; alpha is reached again by the cycle through delta
        assertThat(reached(result)).containsExactly(tuple("alpha", 0), tuple("Beta", 1), tuple("gamma", 1),
                tuple("delta", 2));
    }

    @Test
    void aTraversalBindsTheDepthsFromTheMinimumToTheMaximum() {
        assertThat(reached(run(traversalFromAlpha(0, 0, "Next")))).containsExactly(tuple("alpha", 0));
        assertThat(reached(run(traversalFromAlpha(0, 1, "Next"))))
                .containsExactly(tuple("alpha", 0), tuple("Beta", 1), tuple("gamma", 1));
        assertThat(reached(run(traversalFromAlpha(1, 1, "Next")))).containsExactly(tuple("Beta", 1), tuple("gamma", 1));
        assertThat(reached(run(traversalFromAlpha(2, 5, "Next")))).containsExactly(tuple("delta", 2));
        assertThat(reached(run(traversalFromAlpha(3, 5, "Next")))).isEmpty();
    }

    @Test
    void theRelationshipOfADepthZeroRowIsEmptyAndTheOthersAreTheOnesThatReachedTheEntity() {
        QueryResult.Rows result = run(traversalFromAlpha(0, 10, "Next"));

        assertThat(result.rows()).extracting(row -> row.relationship("via").map(state -> weightOf(state.values())))
                .containsExactly(Optional.empty(), Optional.of(1), Optional.of(4), Optional.of(3));
    }

    private static int weightOf(Map<String, Value> values) {
        return ((Value.IntValue) values.get("weight")).value();
    }

    @Test
    void aTraversalLeadsByInverseNamesToo() {
        assertThat(reached(run(traversalFromAlpha(1, 10, "previous")))).containsExactly(tuple("delta", 1),
                tuple("gamma", 2), tuple("Beta", 3));
    }

    @Test
    void aTraversalReachesWhatTheViewTraversesAtTheDepthsItGives() {
        EntityId alpha = items.id(I1);
        List<Step> traversed = items.view().traverse(alpha, "Next", relationship -> true, 10);

        QueryResult.Rows result = run(traversalFromAlpha(1, 10, "Next"));

        assertThat(result.rows()).extracting(row -> row.entity("t").orElseThrow().id())
                .containsExactlyInAnyOrderElementsOf(traversed.stream().map(step -> step.entity().id()).toList());
        assertThat(result.rows()).extracting(row -> row.entity("t").orElseThrow().id(),
                row -> row.relationship("via").orElseThrow().id()).containsExactlyInAnyOrderElementsOf(
                        traversed.stream().map(step -> tuple(step.entity().id(), step.via().id())).toList());
    }

    @Test
    void aTraversalWorksOnEachRowAndDropsRowsWithoutAnEntityToStartFrom() {
        Query query = Query.from("Item", "i").optionalFollow("i", "previous", "p")
                .traverse("p", "Next", 0, 1, "t", Optional.empty(), Optional.empty())
                .build();

        QueryResult.Rows result = run(query);

        // alpha's only previous item is delta, from which Next leads to alpha; the items without a previous item have
        // no row, because there is nothing to start from
        assertThat(result.rows()).extracting(row -> QueryRows.text(row, "i", "label")).doesNotContain(FULL_WIDTH_A, EMOJI);
        assertThat(result.rows()).extracting(row -> QueryRows.text(row, "i", "label"),
                row -> QueryRows.text(row, "p", "label"), row -> QueryRows.text(row, "t", "label"))
                .contains(tuple("alpha", "delta", "delta"), tuple("alpha", "delta", "alpha"));
    }
}
