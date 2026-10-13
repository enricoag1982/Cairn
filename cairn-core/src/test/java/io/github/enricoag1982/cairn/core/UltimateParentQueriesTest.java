package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.decimal;
import static io.github.enricoag1982.cairn.core.QueryRows.name;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The total balance by ultimate parent (RD-6) over ownership chains that are longer, wider and cyclic, and what the
 * maximum depth of a traversal does to them (the risks of design {@code add-queries-and-aggregation}).
 */
class UltimateParentQueriesTest {

    private final ReferenceStore reference = new ReferenceStore();
    private EntityId acme;
    private EntityId beta;

    /** ACME owns 60% of Beta from 1 February; A-1 is held by Beta and A-2 by ACME, with 1,000 and 2,000. */
    @BeforeEach
    void setUp() {
        reference.createAccounts();
        acme = entity(reference.parties, ACME);
        beta = entity(reference.parties, BETA);
        reference.commit(Transaction.by("importer").relate("Owns", acme, beta, from(2, 1), values("share", 60)).build());
    }

    private EntityId newOrganization(String name) {
        return entity(reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", name)).build()), GAMMA);
    }

    private void owns(EntityId owner, EntityId owned, int share) {
        reference.commit(Transaction.by("importer").relate("Owns", owner, owned, from(2, 1), values("share", share))
                .build());
    }

    private static Query totalByUltimateParent(int maxDepth) {
        return Query.from("Account", "a")
                .follow("a", "HeldBy", "holder")
                .traverse("holder", "ownedBy", 0, maxDepth, "party")
                .where(Condition.not(Condition.hasLink("party", "ownedBy")))
                .groupBy("party")
                .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"), Aggregate.count("accounts"))
                .orderBy(Order.ascending(Column.key("party")))
                .build();
    }

    private QueryResult.Rows run(Query query) {
        return rows(reference.view(on(3, 31)).query(query));
    }

    @Test
    void anOwnershipChainOfThreeLeadsEveryAccountToItsEnd() {
        EntityId gamma = newOrganization("Gamma");
        owns(gamma, acme, 60);

        QueryResult.Rows result = run(totalByUltimateParent(10));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(name(row, "party")).isEqualTo("Gamma");
            assertThat(row.entity("party").orElseThrow().id()).isEqualTo(gamma);
            assertThat(decimal(row, "total")).isEqualByComparingTo("3000");
            assertThat(row.value("accounts")).contains(Value.of(2L));
        });
    }

    @Test
    void anAccountOfAPartyWithTwoUltimateParentsCountsInBothGroups() {
        EntityId delta = newOrganization("Delta");
        owns(delta, beta, 30);

        QueryResult.Rows result = run(totalByUltimateParent(10));

        assertThat(result.rows()).extracting(row -> name(row, "party"), row -> decimal(row, "total").intValueExact())
                .containsExactlyInAnyOrder(tuple("ACME", 3000), tuple("Delta", 1000));
    }

    @Test
    void anOwnershipCycleEndsTheTraversalAndLeavesNoUltimateParent() {
        owns(beta, acme, 60);

        QueryResult.Rows result = run(totalByUltimateParent(10));

        assertThat(result.rows()).as("every party in the cycle has an owner").isEmpty();
    }

    @Test
    void theMaximumDepthCanCutAChainShort() {
        EntityId gamma = newOrganization("Gamma");
        owns(gamma, acme, 60);

        QueryResult.Rows deepEnough = run(totalByUltimateParent(2));
        QueryResult.Rows tooShallow = run(totalByUltimateParent(1));

        assertThat(deepEnough.rows()).singleElement().satisfies(row -> {
            assertThat(name(row, "party")).isEqualTo("Gamma");
            assertThat(decimal(row, "total")).isEqualByComparingTo("3000");
        });
        assertThat(tooShallow.rows()).as("only A-2 reaches Gamma within one step").singleElement().satisfies(row -> {
            assertThat(name(row, "party")).isEqualTo("Gamma");
            assertThat(decimal(row, "total")).isEqualByComparingTo("2000");
        });
    }
}
