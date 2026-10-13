package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.EFFECTIVE_TIMES;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Queries give the same rows in the same order whatever stores the data (design D1 and D2 of
 * {@code add-queries-and-aggregation}): a durable store and an in-memory store, with the same clock and the same
 * deterministic ID generator, are told the stories RD-1 to RD-5, and answer the same queries at the same coordinates, also
 * after the durable store is closed and opened again.
 */
class DurableQueriesTest {

    @TempDir
    Path directory;

    private DurableFixture durable;
    private final TestClock memoryClock = new TestClock();
    private Store memory;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
        memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build());
        DurableFixture.registerReferenceModel(memory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    private static List<Query> queries() {
        return List.of(
                Query.from("Account", "a")
                        .follow("a", "HeldBy", "holder")
                        .traverse("holder", "ownedBy", 0, 10, "party")
                        .where(Condition.not(Condition.hasLink("party", "ownedBy")))
                        .groupBy("party")
                        .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"))
                        .build(),
                Query.from("Party", "p").build(),
                Query.from("Organization", "o").optionalFollow("o", "Employs", "employee", "employs").build(),
                Query.from("Organization", "o")
                        .traverse("o", "ownedBy", 0, 5, "owner", Optional.of("via"), Optional.of("depth"))
                        .orderBy(Order.descending(Column.depth("depth")))
                        .build(),
                Query.from("Account", "a")
                        .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(1200)))
                        .orderBy(Order.descending(Column.attribute("a", "balance"))).limit(1).build());
    }

    private static List<QueryResult> answers(Store store) {
        List<QueryResult> answers = new ArrayList<>();
        for (var effectiveAt : EFFECTIVE_TIMES) {
            for (Coordinates coordinates : List.of(Coordinates.effectiveAt(effectiveAt),
                    Coordinates.effectiveAt(effectiveAt).knownAt(on(4, 5)))) {
                for (Query query : queries()) {
                    answers.add(store.view(coordinates).query(query));
                }
            }
        }
        return answers;
    }

    @Test
    void aDurableStoreAnswersQueriesLikeAnInMemoryStoreBeforeAndAfterReopening() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.rd1ToRd5(memory, memoryClock);
        ReferenceTales.rd1ToRd5(store, durable.clock);

        List<QueryResult> expected = answers(memory);

        assertThat(expected).as("the queries have answers").anySatisfy(result -> assertThat(result)
                .isInstanceOfSatisfying(QueryResult.Rows.class, rows -> assertThat(rows.rows()).isNotEmpty()));
        assertThat(expected).as("the queries are valid").allSatisfy(result ->
                assertThat(result).isInstanceOf(QueryResult.Rows.class));
        assertThat(answers(store)).isEqualTo(expected);
        store.close();
        DirectoryStore reopened = durable.open();
        assertThat(answers(reopened)).isEqualTo(expected);
    }
}
