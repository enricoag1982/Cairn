package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.ReferenceDomain.entityId;
import static io.github.enricoag1982.cairn.codegen.ReferenceDomain.relationshipId;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.documents.Queries;
import io.github.enricoag1982.cairn.documents.QueryReadResult;
import io.github.enricoag1982.cairn.fixtures.ReferenceQueries;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.reference.types.Account;
import io.github.enricoag1982.cairn.reference.types.HeldBy;
import io.github.enricoag1982.cairn.reference.types.Organization;
import io.github.enricoag1982.cairn.reference.types.Party;
import io.github.enricoag1982.cairn.reference.types.Person;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The same reference queries, RD-6 and RD-10, built with the Java builder, with the typed references of generated types,
 * and written as JSON, are the same query and give the same rows (confirmation of ADR-0023, task 4.2 of
 * {@code add-queries-and-aggregation}).
 */
class QueryFormsAgreeTest {

    /** RD-6 as JSON. */
    private static final String TOTAL_BALANCE = """
            {
              "from": { "type": "Account", "as": "a" },
              "steps": [
                { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } },
                { "traverse": { "from": "holder", "name": "ownedBy", "minDepth": 0, "maxDepth": 10, "as": "party" } },
                { "where": { "not": { "hasLink": { "of": "party", "name": "ownedBy" } } } }
              ],
              "groupBy": [ { "of": "party" } ],
              "aggregates": [ { "function": "sum", "attribute": "balance", "of": "a", "as": "total" } ]
            }
            """;

    /** RD-10, the parties, as YAML. */
    private static final String PARTIES = "from: {type: Party, as: p}\n";

    /** RD-10, the organizations, as JSON. */
    private static final String ORGANIZATIONS = "{ \"from\": { \"type\": \"Organization\", \"as\": \"o\" } }";

    private final ReferenceDomain domain = new ReferenceDomain();

    /** RD-1, RD-5 and RD-2, with the corrections of both, in the order of their dates. */
    private void recordTheStories() {
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        EntityId acme = entityId(parties, ACME);
        EntityId beta = entityId(parties, BETA);
        Receipt accounts = domain.replay(ReferenceStories.rd5Accounts(beta, acme));
        Receipt ownership = domain.replay(ReferenceStories.rd2Ownership(acme, beta));
        EntityId a1 = entityId(accounts, A1);
        domain.replay(ReferenceStories.rd5BalancePatch(a1));
        domain.replay(ReferenceStories.rd5BalanceCorrection(a1));
        domain.replay(ReferenceStories.rd2ShareCorrection(relationshipId(ownership, ACME_OWNS_BETA)));
    }

    private static Query json(String text) {
        QueryReadResult result = Queries.read(text);
        assertThat(result).as(text).isInstanceOf(QueryReadResult.Read.class);
        return ((QueryReadResult.Read) result).query().query();
    }

    private static QueryResult.Rows rows(QueryResult result) {
        assertThat(result).isInstanceOf(QueryResult.Rows.class);
        return (QueryResult.Rows) result;
    }

    @Test
    void theTotalBalanceByUltimateParentIsTheSameQueryInEveryForm() {
        recordTheStories();
        Query builder = ReferenceQueries.totalBalanceByUltimateParent();
        Query typed = Query.from(Account.TYPE, "a")
                .follow("a", HeldBy.TYPE, "holder")
                .traverse("holder", "ownedBy", 0, 10, "party")
                .where(Condition.not(Condition.hasLink("party", "ownedBy")))
                .groupBy("party")
                .aggregate(Account.BALANCE.of("a").sum("total"))
                .build();
        Query written = json(TOTAL_BALANCE);

        assertThat(typed).isEqualTo(builder);
        assertThat(written).isEqualTo(builder);
        for (Coordinates coordinates : List.of(Coordinates.effectiveAt(on(3, 31)),
                Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5)))) {
            View view = domain.store.view(coordinates);
            QueryResult expected = view.query(builder);

            assertThat(view.query(typed)).isEqualTo(expected);
            assertThat(view.query(written)).isEqualTo(expected);
            assertThat(rows(expected).rows()).as("one row for ACME").singleElement().satisfies(row -> {
                assertThat(Organization.from(row.entity("party").orElseThrow()).name()).isEqualTo("ACME");
                assertThat(row.value("total")).isPresent();
            });
        }
        assertThat(totalOf(domain.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5))), typed))
                .isEqualByComparingTo("3500");
        assertThat(totalOf(domain.view(on(3, 31)), written)).isEqualByComparingTo("3200");
    }

    private static BigDecimal totalOf(View view, Query query) {
        return ((Value.DecimalValue) rows(view.query(query)).rows().getFirst().value("total").orElseThrow()).value();
    }

    /** The three forms of one query. */
    private record Forms(Query builder, Query typed, Query written) {
    }

    @Test
    void thePartiesAndTheOrganizationsAreTheSameQueriesInEveryForm() {
        domain.replay(ReferenceStories.rd1Parties());
        View view = domain.view(on(3, 31));

        for (Forms forms : List.of(
                new Forms(ReferenceQueries.parties(), Query.from(Party.TYPE, "p").build(), json(PARTIES)),
                new Forms(ReferenceQueries.organizations(), Query.from(Organization.TYPE, "o").build(),
                        json(ORGANIZATIONS)))) {
            assertThat(forms.typed()).isEqualTo(forms.builder());
            assertThat(forms.written()).isEqualTo(forms.builder());
            assertThat(view.query(forms.typed())).isEqualTo(view.query(forms.builder()));
            assertThat(view.query(forms.written())).isEqualTo(view.query(forms.builder()));
        }
        List<String> names = rows(view.query(json(PARTIES))).rows().stream()
                .map(row -> Party.from(row.entity("p").orElseThrow()).name()).toList();
        assertThat(names).containsExactly("ACME", "Beta", "Alice");
        assertThat(rows(view.query(json(ORGANIZATIONS))).rows().stream()
                .map(row -> Organization.from(row.entity("o").orElseThrow()).name()).toList())
                .containsExactly("ACME", "Beta");
        assertThat(Party.from(rows(view.query(json(PARTIES))).rows().get(0).entity("p").orElseThrow()))
                .as("rows are read as the generated type of their state").isInstanceOf(Organization.class);
        assertThat(Party.from(rows(view.query(json(PARTIES))).rows().get(2).entity("p").orElseThrow()))
                .isInstanceOf(Person.class);
    }
}
