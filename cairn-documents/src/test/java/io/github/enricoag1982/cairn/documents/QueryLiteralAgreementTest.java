package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The engine reads a string literal that is compared with a date or an instant attribute as the readers of documents read
 * dates and instants (design D2 of {@code add-queries-and-aggregation}). The engine does not depend on this module, so it
 * repeats the patterns of {@link ValuesSchema}; this test compares what the two accept.
 */
class QueryLiteralAgreementTest {

    private static final List<String> TEXTS = List.of(
            "2026-03-31", "2026-02-30", "2026-3-31", "+12345-01-01", "-0001-01-01", "-0000-01-01", "20260331", " 2026-03-31",
            "2026-03-31 ", "2026-03-31\n", "2026-03-31T00:00:00Z", "2026-03-31T00:00:00z", "2026-03-31t00:00:00Z",
            "2026-03-31T00:00:00+01:00", "2026-03-31T00:00:00+0100", "2026-03-31T00:00:00+01:00:30",
            "2026-03-31T00:00Z", "2026-03-31T00:00:00", "2026-03-31T00:00:00.123456789Z", "2026-03-31T00:00:00.Z",
            "2026-03-31T00:00:00.1234567890Z", "2026-13-31T00:00:00Z", "2026-03-31T25:00:00Z", "2026-03-31T00:00:00+19:00",
            "+12345-03-31T00:00:00Z", "12345-03-31T00:00:00Z", "yesterday", "", "1", "true");

    private final TestClock clock = new TestClock();
    private final Store store = Stores.inMemory(StoreSettings.builder().clock(clock)
            .idGenerator(IdGenerators.deterministic()).build());

    QueryLiteralAgreementTest() {
        SchemaDefinition definition = SchemaDefinition.builder()
                .entityType("Event", event -> event.attribute("day", ValueType.DATE).attribute("at", ValueType.INSTANT))
                .build();
        assertThat(store.schema().register(new Origin("events", "1.0.0"), definition))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    private static boolean comparable(View view, String attribute, String text) {
        Query query = Query.from("Event", "e").where(Condition.eq(Operand.attribute("e", attribute), Operand.literal(text)))
                .build();
        QueryResult result = view.query(query);
        if (result instanceof QueryResult.Invalid invalid) {
            assertThat(invalid.problems()).singleElement().satisfies(problem ->
                    assertThat(problem.code()).isEqualTo(ProblemCode.INCOMPARABLE_VALUES));
            return false;
        }
        return true;
    }

    @Test
    void aStringIsADateForTheEngineExactlyWhereTheDocumentReadersReadOne() {
        View view = store.view(Coordinates.effectiveAt(ReferenceStories.on(3, 1)));

        for (String text : TEXTS) {
            assertThat(comparable(view, "day", text)).as("a date: '%s'", text).isEqualTo(Scalars.date(text).isPresent());
        }
    }

    @Test
    void aStringIsAnInstantForTheEngineExactlyWhereTheDocumentReadersReadOne() {
        View view = store.view(Coordinates.effectiveAt(ReferenceStories.on(3, 1)));

        for (String text : TEXTS) {
            assertThat(comparable(view, "at", text)).as("an instant: '%s'", text).isEqualTo(Instants.read(text).isPresent());
        }
    }

    @Test
    void theInstantTheEngineReadsIsTheInstantTheDocumentReadersRead() {
        store.commit(Transaction.by("test").create("Event", ReferenceStories.from(1, 1),
                ReferenceStories.values("at", Value.of(Instants.read("2026-03-31T00:00:00+01:00").orElseThrow()))).build());
        View view = store.view(Coordinates.effectiveAt(ReferenceStories.on(4, 1)));

        for (String text : List.of("2026-03-30T23:00:00Z", "2026-03-31T00:00:00+01:00", "2026-03-31T00:00:00.000+01:00")) {
            QueryResult result = view.query(Query.from("Event", "e")
                    .where(Condition.eq(Operand.attribute("e", "at"), Operand.literal(text))).build());

            assertThat(result).as(text).isInstanceOfSatisfying(QueryResult.Rows.class,
                    rows -> assertThat(rows.rows()).hasSize(1));
        }
    }
}
