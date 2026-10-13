package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.name;
import static io.github.enricoag1982.cairn.core.QueryRows.names;
import static io.github.enricoag1982.cairn.core.QueryRows.onlyProblem;
import static io.github.enricoag1982.cairn.core.QueryRows.problems;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ZERO_OR_MORE;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.GroupKey;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Traversals that follow only the relationships that meet a condition, and the type of the entity that a traversal binds
 * (design D1 and D2 of {@code add-queries-and-aggregation}): which entities are reached, what the condition may refer
 * to, and which attributes the reached binding has, with a minimum depth of 0 and without one, and when the types at
 * the two ends are unrelated.
 */
class QueryTraversalsTest {

    private static final TemporaryId A3 = new TemporaryId("a3");

    private final ReferenceStore reference = new ReferenceStore();

    private static Condition shareAbove(int share) {
        return Condition.gt(Operand.attribute("owns", "share"), Operand.literal(share));
    }

    private EntityId newOrganization(TemporaryId id, String name) {
        return entity(reference.commit(Transaction.by("importer")
                .create(id, "Organization", from(1, 1), values("name", name)).build()), id);
    }

    private void owns(EntityId owner, EntityId owned, int share) {
        reference.commit(Transaction.by("importer").relate("Owns", owner, owned, from(2, 1), values("share", share))
                .build());
    }

    /** ACME owns 60% of Beta, and Gamma owns 30% of ACME and 20% of Beta. */
    private void ownershipsOfBeta() {
        reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        EntityId gamma = newOrganization(GAMMA, "Gamma");
        owns(acme, beta, 60);
        owns(gamma, acme, 30);
        owns(gamma, beta, 20);
    }

    private QueryResult run(Query query) {
        return reference.view(on(3, 31)).query(query);
    }

    // ------------------------------------------------------------------ following only some relationships

    private static Query ownersOfBeta(Optional<Condition> along, int minDepth) {
        return Query.from("Organization", "o")
                .where(Condition.eq(Operand.attribute("o", "name"), Operand.literal("Beta")))
                .traverse("o", "ownedBy", minDepth, 5, "owner", Optional.of("owns"), Optional.of("depth"), along)
                .orderBy(Order.ascending(Column.depth("depth")), Order.ascending(Column.attribute("owner", "name")))
                .build();
    }

    @Test
    void aTraversalFollowsOnlyTheRelationshipsThatMeetItsCondition() {
        ownershipsOfBeta();

        QueryResult.Rows all = rows(run(ownersOfBeta(Optional.empty(), 1)));
        QueryResult.Rows large = rows(run(ownersOfBeta(Optional.of(shareAbove(50)), 1)));

        assertThat(all.rows()).extracting(row -> name(row, "owner"), row -> row.value("depth").orElseThrow())
                .containsExactly(tuple("ACME", Value.of(1)), tuple("Gamma", Value.of(1)));
        assertThat(large.rows()).as("Gamma holds 20% of Beta and 30% of ACME, so only ACME is reached")
                .extracting(row -> name(row, "owner"), row -> row.value("depth").orElseThrow())
                .containsExactly(tuple("ACME", Value.of(1)));
    }

    @Test
    void anEntityIsReachedAtTheSmallestDepthThroughTheRelationshipsThatAreFollowed() {
        ownershipsOfBeta();
        Condition ownersOfMoreThan25 = shareAbove(25);

        QueryResult.Rows result = rows(run(ownersOfBeta(Optional.of(ownersOfMoreThan25), 1)));

        assertThat(result.rows()).as("Gamma's 20% of Beta is not followed, its 30% of ACME is")
                .extracting(row -> name(row, "owner"), row -> row.value("depth").orElseThrow())
                .containsExactly(tuple("ACME", Value.of(1)), tuple("Gamma", Value.of(2)));
    }

    @Test
    void theConditionOfATraversalDoesNotApplyToTheEntityItStartsFrom() {
        ownershipsOfBeta();
        Condition never = Condition.eq(Operand.literal(1), Operand.literal(2));

        QueryResult.Rows result = rows(run(ownersOfBeta(Optional.of(never), 0)));

        assertThat(result.rows()).extracting(row -> name(row, "owner"), row -> row.value("depth").orElseThrow())
                .containsExactly(tuple("Beta", Value.of(0)));
    }

    @Test
    void aConditionOfLiteralsDecidesForEveryRelationshipAtOnce() {
        ownershipsOfBeta();
        Condition always = Condition.eq(Operand.literal(1), Operand.literal(1));
        Condition never = Condition.eq(Operand.literal(1), Operand.literal(2));

        QueryResult.Rows followed = rows(run(ownersOfBeta(Optional.of(always), 1)));
        QueryResult.Rows notFollowed = rows(run(ownersOfBeta(Optional.of(never), 1)));

        assertThat(names(followed, "owner")).containsExactly("ACME", "Gamma");
        assertThat(notFollowed.rows()).isEmpty();
    }

    @Test
    void theConditionCanTestAnyAttributeOfTheRelationshipAndAMissingValueIsNotFollowed() {
        Receipt parties = reference.createParties();
        reference.replay(ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        reference.commit(Transaction.by("importer").relate("Employs", entity(parties, ACME), entity(parties, ALICE),
                from(3, 1), values()).build());
        Query query = employees(Condition.hasValue("job", "title"));
        Query analysts = employees(Condition.eq(Operand.attribute("job", "title"), Operand.literal("Analyst")));
        Query bosses = employees(Condition.eq(Operand.attribute("job", "title"), Operand.literal("Boss")));

        assertThat(names(rows(run(query)), "employer")).as("ACME's job has no title").containsExactly("Beta");
        assertThat(names(rows(run(analysts)), "employer")).containsExactly("Beta");
        assertThat(rows(run(bosses)).rows()).isEmpty();
    }

    private static Query employees(Condition along) {
        return Query.from("Person", "p")
                .traverse("p", "employedBy", 1, 1, "employer", Optional.of("job"), Optional.empty(),
                        Optional.of(along))
                .orderBy(Order.ascending(Column.attribute("employer", "name")))
                .build();
    }

    @Test
    void theConditionOfATraversalCanBeAnythingAConditionCanBe() {
        ownershipsOfBeta();
        Condition inList = Condition.in(Operand.attribute("owns", "share"), Value.of(60), Value.of(20));
        Condition condition = Condition.or(Condition.and(inList, Condition.hasValue("owns", "share")), shareAbove(99));

        QueryResult.Rows result = rows(run(ownersOfBeta(Optional.of(condition), 1)));

        assertThat(names(result, "owner")).as("60% and 20% are in the list, 30% is not").containsExactly("ACME", "Gamma");
    }

    // ------------------------------------------------------------------ checking what the condition refers to

    private static Query along(Condition condition) {
        return Query.from("Organization", "o")
                .where(Condition.hasLink("o", "ownedBy"))
                .traverse("o", "ownedBy", 1, 3, "owner", Optional.of("owns"), Optional.of("depth"),
                        Optional.of(condition))
                .build();
    }

    @Test
    void theConditionOfATraversalCannotReferToAnotherBinding() {
        Problem problem = onlyProblem(run(along(Condition.eq(Operand.attribute("o", "name"), Operand.literal("Beta")))));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_BINDING);
        assertThat(problem.path()).isEqualTo("@query.steps.1");
        assertThat(problem.message()).contains("along", "'owns'", "'o'");
        assertThat(problem.hint()).isPresent();
    }

    @Test
    void theConditionOfATraversalCannotReferToItsDepthOrTheEntityItBinds() {
        List<Problem> problems = problems(run(along(Condition.and(
                Condition.gt(Operand.attribute("owner", "name"), Operand.literal("A")),
                Condition.hasLink("owner", "ownedBy"),
                Condition.eq(Operand.id("depth"), Operand.literal("1"))))));

        assertThat(problems).extracting(Problem::code, Problem::path)
                .containsOnly(tuple(ProblemCode.UNKNOWN_BINDING, "@query.steps.1"));
        assertThat(problems).hasSize(3);
    }

    @Test
    void aBindingThatLaterStepsBindIsNoMoreVisibleToTheConditionThanOneOfTheQuery() {
        Query query = Query.from("Organization", "o")
                .traverse("o", "ownedBy", 1, 3, "owner", Optional.of("owns"), Optional.empty(),
                        Optional.of(Condition.hasValue("later", "name")))
                .follow("o", "Employs", "later")
                .build();

        Problem problem = onlyProblem(run(query));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_BINDING);
        assertThat(problem.path()).isEqualTo("@query.steps.0");
    }

    @Test
    void theConditionOfATraversalThatDoesNotNameItsRelationshipHasNoBindingToReferTo() {
        Query query = Query.from("Organization", "o")
                .traverse("o", "ownedBy", 1, 3, "owner", Optional.empty(), Optional.empty(),
                        Optional.of(Condition.gt(Operand.attribute("owns", "share"), Operand.literal(50))))
                .build();

        Problem problem = onlyProblem(run(query));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_BINDING);
        assertThat(problem.path()).isEqualTo("@query.steps.0");
        assertThat(problem.message()).contains("does not name it");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Name the relationship"));
    }

    @Test
    void theRelationshipOfATraversalIsNoEntityToTestLinksOrTypesOf() {
        List<Problem> problems = problems(run(along(Condition.and(Condition.hasLink("owns", "ownedBy"),
                Condition.isA("owns", "Organization")))));

        assertThat(problems).hasSize(2).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_BINDING);
            assertThat(problem.path()).isEqualTo("@query.steps.1");
            assertThat(problem.message()).contains("holds a relationship");
        });
    }

    @Test
    void anAttributeThatTheRelationshipTypeLacksIsUnknownWithTheClosestOne() {
        Problem problem = onlyProblem(run(along(Condition.gt(Operand.attribute("owns", "shares"),
                Operand.literal(50)))));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(problem.path()).isEqualTo("@query.steps.1");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'share'"));
    }

    @Test
    void valuesThatCannotBeComparedAreIncomparableInTheConditionOfATraversalToo() {
        Problem problem = onlyProblem(run(along(Condition.gt(Operand.attribute("owns", "share"),
                Operand.literal("many")))));

        assertThat(problem.code()).isEqualTo(ProblemCode.INCOMPARABLE_VALUES);
        assertThat(problem.path()).isEqualTo("@query.steps.1");
    }

    @Test
    void theProblemsOfTheConditionAndOfTheRestOfTheQueryAreReportedTogether() {
        Query query = Query.from("Organization", "o")
                .traverse("o", "ownedBy", 1, 3, "owner", Optional.of("owns"), Optional.empty(),
                        Optional.of(Condition.gt(Operand.attribute("o", "name"), Operand.literal("A"))))
                .where(Condition.hasValue("owner", "leii"))
                .build();

        List<Problem> problems = problems(run(query));

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.UNKNOWN_BINDING, "@query.steps.0"),
                tuple(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.1"));
    }

    // ------------------------------------------------------------------ the type of the entity a traversal binds

    private static Query reached(String start, String name, int minDepth, Condition filter) {
        return Query.from(start, "s")
                .traverse("s", name, minDepth, 3, "party")
                .where(filter)
                .build();
    }

    private static Condition hasValue(String attribute) {
        return Condition.hasValue("party", attribute);
    }

    @Test
    void aTraversalFromDepth0BindsTheClosestCommonSupertypeOfBothEnds() {
        // ownedBy leads to the source of Owns, an Organization; Party is the type that an Organization and a Person share
        assertThat(run(reached("Person", "ownedBy", 0, hasValue("name")))).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(reached("Party", "ownedBy", 0, hasValue("name")))).isInstanceOf(QueryResult.Rows.class);

        for (String attribute : List.of("lei", "birthDate")) {
            Problem problem = onlyProblem(run(reached("Person", "ownedBy", 0, hasValue(attribute))));
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
            assertThat(problem.path()).isEqualTo("@query.steps.1");
            assertThat(problem.message()).contains("entity type Party");
        }
        assertThat(onlyProblem(run(reached("Party", "ownedBy", 0, hasValue("lei")))).code())
                .as("the holder can be a Person").isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
    }

    @Test
    void theTypeIsTheFarEndWithoutADepthOfZeroOrWhenTheStartTypeIsTheSame() {
        assertThat(run(reached("Party", "ownedBy", 1, hasValue("lei")))).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(reached("Person", "ownedBy", 1, hasValue("lei")))).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(reached("Organization", "ownedBy", 0, hasValue("lei")))).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(reached("Organization", "Employs", 1, hasValue("birthDate"))))
                .isInstanceOf(QueryResult.Rows.class);
    }

    @Test
    void theTypeOfTheStartCanBeTheSubtypeOrTheSupertypeOfTheFarEnd() {
        // Owns leads from an Organization to a Party: an Organization start is a subtype of the far end
        Problem subtype = onlyProblem(run(reached("Organization", "Owns", 0, hasValue("lei"))));
        // employedBy leads to an Organization from a Person: a Party start is a supertype of the far end
        Problem supertype = onlyProblem(run(reached("Party", "employedBy", 0, hasValue("lei"))));

        assertThat(subtype.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(supertype.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(run(reached("Organization", "Owns", 0, hasValue("name")))).isInstanceOf(QueryResult.Rows.class);
        assertThat(run(reached("Party", "employedBy", 0, hasValue("name")))).isInstanceOf(QueryResult.Rows.class);
    }

    @Test
    void theHolderOfAnAccountThatIsAPersonIsBoundAsAPartyAtDepth0() {
        reference.createAccounts();
        EntityId alice = entity(reference.parties, ALICE);
        reference.commit(Transaction.by("importer")
                .create(A3, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance", money("300")))
                .relate(new TemporaryId("a3-held-by"), "HeldBy", A3, alice, from(1, 1), values()).build());
        Query query = Query.from("Account", "a")
                .follow("a", "HeldBy", "holder")
                .traverse("holder", "ownedBy", 0, 5, "party", Optional.empty(), Optional.of("depth"), Optional.empty())
                .where(Condition.hasValue("party", "name"))
                .select(Column.attribute("a", "number"), Column.attribute("party", "name"), Column.depth("depth"))
                .orderBy(Order.ascending(Column.attribute("a", "number")))
                .build();

        QueryResult.Rows result = rows(run(query));

        assertThat(result.rows()).extracting(row -> row.value("a.number").orElseThrow(),
                row -> row.value("party.name").orElseThrow(), row -> row.value("depth").orElseThrow())
                .containsExactly(tuple(Value.of("A-1"), Value.of("Beta"), Value.of(0)),
                        tuple(Value.of("A-2"), Value.of("ACME"), Value.of(0)),
                        tuple(Value.of("A-3"), Value.of("Alice"), Value.of(0)));
    }

    // ------------------------------------------------------------------ unrelated types

    /** Two entity types without a common supertype that both have {@code size}, a string and an int. */
    private static SchemaDefinition garmentsAndParcels() {
        return SchemaDefinition.builder()
                .entityType("Garment", e -> e.attribute("size", STRING))
                .entityType("Parcel", e -> e.attribute("size", INT))
                .relationshipType("Wraps", r -> r.from("Garment").to("Parcel")
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE).inverse("wrappedBy"))
                .build();
    }

    private ReferenceStore garments() {
        ReferenceStore store = ReferenceStore.with(garmentsAndParcels());
        TemporaryId garment = new TemporaryId("garment");
        TemporaryId parcel = new TemporaryId("parcel");
        store.commit(Transaction.by("test")
                .create(garment, "Garment", from(1, 1), values("size", "L"))
                .create(parcel, "Parcel", from(1, 1), values("size", 5))
                .relate("Wraps", garment, parcel, from(1, 1), values())
                .build());
        return store;
    }

    private static Query wrapped(int minDepth) {
        return Query.from("Garment", "g").traverse("g", "Wraps", minDepth, 2, "x", Optional.empty(),
                Optional.of("depth"), Optional.empty()).build();
    }

    private static QueryResult runOn(ReferenceStore store, Query query) {
        return store.view(on(3, 1)).query(query);
    }

    @Test
    void aBindingThatCanHoldEntitiesOfUnrelatedTypesHasNoAttributesToRead() {
        ReferenceStore store = garments();
        Operand.AttributeOf size = Operand.attribute("x", "size");
        List<Query> queries = List.of(
                append(wrapped(0), q -> q.where(Condition.gt(size, Operand.literal(3)))),
                append(wrapped(0), q -> q.where(Condition.hasValue("x", "size"))),
                append(wrapped(0), q -> q.aggregate(Aggregate.sum(size, "total"))),
                append(wrapped(0), q -> q.groupBy(GroupKey.attribute("x", "size"))),
                append(wrapped(0), q -> q.select(Column.attribute("x", "size"))),
                append(wrapped(0), q -> q.orderBy(Order.ascending(Column.attribute("x", "size")))));

        for (Query query : queries) {
            Problem problem = onlyProblem(runOn(store, query));

            assertThat(problem.code()).as(query.toString()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
            assertThat(problem.message()).contains("'x'", "Garment and Parcel", "no common supertype");
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("depth 1"));
        }
    }

    /** A query from the builder of another query's start and steps, with more added. */
    private static Query append(Query base, Consumer<Query.Builder> more) {
        Query.Builder builder = Query.from("Garment", "g");
        base.steps().forEach(builder::step);
        more.accept(builder);
        return builder.build();
    }

    @Test
    void aBindingOfUnrelatedTypesStillHasItsIdItsTypeItsLinksAndItsType() {
        ReferenceStore store = garments();
        Query query = append(wrapped(0), builder -> builder
                .where(Condition.or(Condition.isA("x", "Parcel"), Condition.hasLink("x", "Wraps"),
                        Condition.hasLink("x", "wrappedBy")))
                .where(Condition.in(Operand.type("x"), Value.of("Garment"), Value.of("Parcel")))
                .orderBy(Order.ascending(Column.depth("depth"))));

        QueryResult.Rows result = rows(runOn(store, query));

        assertThat(result.rows()).extracting(row -> row.entity("x").orElseThrow().entityType(),
                row -> row.value("depth").orElseThrow()).containsExactly(tuple("Garment", Value.of(0)),
                tuple("Parcel", Value.of(1)));
    }

    @Test
    void groupingByTheBindingItselfWorksWhateverItsType() {
        ReferenceStore store = garments();
        Query query = append(wrapped(0), builder -> builder.groupBy("x").aggregate(Aggregate.count("rows")));

        QueryResult.Rows result = rows(runOn(store, query));

        assertThat(result.rows()).hasSize(2).allSatisfy(row -> assertThat(row.value("rows")).contains(Value.of(1L)));
    }

    @Test
    void withoutTheStartEntityTheBindingHasTheTypeOfTheFarEndAndItsAttributes() {
        ReferenceStore store = garments();
        Query query = append(wrapped(1), builder -> builder.groupBy(GroupKey.binding("g"))
                .aggregate(Aggregate.sum(Operand.attribute("x", "size"), "total"),
                        Aggregate.max(Operand.attribute("x", "size"), "largest")));

        QueryResult.Rows result = rows(runOn(store, query));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.value("total")).isPresent().get().isInstanceOfSatisfying(Value.DecimalValue.class,
                    total -> assertThat(total.value()).isEqualByComparingTo("5"));
            assertThat(row.value("largest")).contains(Value.of(5));
        });
    }

    @Test
    void theTypeThatIsUnrelatedIsPassedOnToTheBindingsThatAreTraversedFromIt() {
        ReferenceStore store = garments();
        Query query = Query.from("Garment", "g")
                .traverse("g", "Wraps", 0, 2, "x")
                .traverse("x", "wrappedBy", 0, 2, "y")
                .aggregate(Aggregate.sum(Operand.attribute("y", "size"), "total"))
                .build();
        Query fromDepth1 = Query.from("Garment", "g")
                .traverse("g", "Wraps", 0, 2, "x")
                .traverse("x", "wrappedBy", 1, 2, "y")
                .where(Condition.hasValue("y", "size"))
                .build();

        Problem problem = onlyProblem(runOn(store, query));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE);
        assertThat(problem.path()).isEqualTo("@query.aggregates.0");
        assertThat(runOn(store, fromDepth1)).as("wrappedBy leads to Garments, which have a size")
                .isInstanceOf(QueryResult.Rows.class);
    }
}
