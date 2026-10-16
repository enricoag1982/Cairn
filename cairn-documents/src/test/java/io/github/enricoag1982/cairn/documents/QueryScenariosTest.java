package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.QueryFixtures.problem;
import static io.github.enricoag1982.cairn.documents.QueryFixtures.query;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceQueries;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Traversal;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code queries} spec that show the JSON form of queries, one test each. */
class QueryScenariosTest {

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    @Test
    @DisplayName("Scenario: The total balance as JSON")
    void theTotalBalanceAsJson() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        EntityId acme = (EntityId) parties.temporaryIds().get(ACME);
        EntityId beta = (EntityId) parties.temporaryIds().get(BETA);
        Receipt balances = reference.replay(ReferenceStories.rd5Accounts(beta, acme));
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(acme, beta));
        EntityId a1 = (EntityId) balances.temporaryIds().get(A1);
        reference.replay(ReferenceStories.rd5BalancePatch(a1));
        reference.replay(ReferenceStories.rd5BalanceCorrection(a1));
        reference.replay(ReferenceStories.rd2ShareCorrection(
                (RelationshipId) ownership.temporaryIds().get(ACME_OWNS_BETA)));

        LocatedQuery read = QueryFixtures.read(QueryFixtures.TOTAL_BALANCE);
        QueryResult fromJson = reference.store.view(Coordinates.effectiveAt(on(3, 31))).query(read.query());
        QueryResult fromJava = reference.store.view(Coordinates.effectiveAt(on(3, 31)))
                .query(ReferenceQueries.totalBalanceByUltimateParent());

        assertThat(read.query()).isEqualTo(ReferenceQueries.totalBalanceByUltimateParent());
        assertThat(fromJson).isEqualTo(fromJava);
        assertThat(fromJson).isInstanceOfSatisfying(QueryResult.Rows.class, rows -> {
            assertThat(rows.columns()).containsExactly("party", "total");
            assertThat(rows.rows()).singleElement().satisfies(row -> {
                assertThat(row.entity("party").orElseThrow().values()).containsEntry("name", Value.of("ACME"));
                assertThat(row.value("total").orElseThrow()).isInstanceOfSatisfying(Value.DecimalValue.class,
                        total -> assertThat(total.value()).isEqualByComparingTo(new BigDecimal("3200")));
            });
        });
    }

    @Test
    @DisplayName("Scenario: Starting from given entities as JSON")
    void startingFromGivenEntitiesAsJson() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        EntityId acme = (EntityId) parties.temporaryIds().get(ACME);
        EntityId beta = (EntityId) parties.temporaryIds().get(BETA);
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        reference.replay(ReferenceStories.rd2Ownership(acme, beta));
        reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate("Owns", GAMMA, acme, from(2, 1), values("share", 70)).build());
        String json = """
                {
                  "from": { "type": "Party", "as": "p", "ids": ["%s", "%s"] },
                  "steps": [
                    { "traverse": { "from": "p", "name": "ownedBy", "minDepth": 0, "as": "owner", "depth": "level" } }
                  ]
                }
                """.formatted(acme.value(), alice.value());
        Query inJava = Query.from("Party", "p").ids(acme, alice)
                .traverse(Traversal.of("p", "ownedBy", "owner").minDepth(0).depth("level"))
                .build();

        LocatedQuery read = QueryFixtures.read(json);
        QueryResult fromJson = reference.store.view(Coordinates.effectiveAt(on(3, 1))).query(read.query());
        QueryResult fromJava = reference.store.view(Coordinates.effectiveAt(on(3, 1))).query(inJava);

        assertThat(read.query()).isEqualTo(inJava);
        assertThat(fromJson).isEqualTo(fromJava);
        assertThat(fromJson).isInstanceOfSatisfying(QueryResult.Rows.class, rows ->
                assertThat(rows.rows()).extracting(
                        row -> row.entity("p", state -> state.values().get("name")).orElseThrow(),
                        row -> row.entity("owner", state -> state.values().get("name")).orElseThrow(),
                        row -> row.intValue("level").orElseThrow())
                        .as("ACME and Alice only, not Beta; ACME is owned by Gamma, with no maximum depth")
                        .containsExactlyInAnyOrder(tuple(Value.of("ACME"), Value.of("ACME"), 0),
                                tuple(Value.of("ACME"), Value.of("Gamma"), 1),
                                tuple(Value.of("Alice"), Value.of("Alice"), 0)));
    }

    @Test
    @DisplayName("Scenario: A JSON query with a misspelled key")
    void aJsonQueryWithAMisspelledKey() {
        String text = """
                {
                  "from": { "type": "Account", "as": "a" },
                  "steps": [
                    { "where": { "hasValue": { "attribute": "balance", "of": "a" } } },
                    { "folow": { "from": "a", "name": "HeldBy", "as": "holder" } }
                  ]
                }
                """;

        Problem problem = problem(text);

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
        assertThat(problem.path()).isEqualTo("@query.steps.1");
        assertThat(problem.found()).contains("folow");
        assertThat(problem.location()).contains(locationOf(text, "\"folow\"", "/steps/1/folow"));
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("follow"));
        assertThat(query(text.replace("folow", "follow")).steps()).hasSize(2);
    }
}
