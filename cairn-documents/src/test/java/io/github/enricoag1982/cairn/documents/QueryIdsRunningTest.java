package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.QueryResult;
import org.junit.jupiter.api.Test;

/**
 * A query written as JSON or YAML that starts from given entities, run on a store (design D3 of
 * {@code add-query-and-traversal-conveniences}): a list of IDs binds those entities only, and an empty list binds none,
 * where no list binds every entity of the type.
 */
class QueryIdsRunningTest {

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    private QueryResult.Rows run(String text) {
        QueryResult result = reference.store.view(Coordinates.effectiveAt(on(3, 31))).query(QueryFixtures.query(text));
        assertThat(result).isInstanceOf(QueryResult.Rows.class);
        return (QueryResult.Rows) result;
    }

    @Test
    void anEmptyListOfIdsGivesNoRowsAndNoListGivesEveryEntityOfTheType() {
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        EntityId acme = (EntityId) parties.temporaryIds().get(ACME);
        EntityId beta = (EntityId) parties.temporaryIds().get(BETA);
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);

        QueryResult.Rows none = run("{ \"from\": { \"type\": \"Party\", \"as\": \"p\", \"ids\": [] } }");
        QueryResult.Rows everyone = run("{ \"from\": { \"type\": \"Party\", \"as\": \"p\" } }");
        QueryResult.Rows given = run("{ \"from\": { \"type\": \"Party\", \"as\": \"p\", \"ids\": [ \"" + beta.value()
                + "\", \"" + acme.value() + "\", \"" + beta.value() + "\" ] } }");
        QueryResult.Rows yaml = run("from: {type: Party, as: p, ids: []}\n");

        assertThat(none.rows()).isEmpty();
        assertThat(none.columns()).containsExactly("p");
        assertThat(yaml.rows()).isEmpty();
        assertThat(everyone.rows()).extracting(row -> row.entity("p").orElseThrow().id())
                .containsExactly(acme, beta, alice);
        assertThat(given.rows()).as("each once, in the order of the IDs of the entities")
                .extracting(row -> row.entity("p").orElseThrow().id()).containsExactly(acme, beta);
    }
}
