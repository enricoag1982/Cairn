package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceQueries;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Cell;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The results of queries as JSON (design D4 of {@code add-queries-and-aggregation}). */
class QueryResultJsonTest {

    private static final Instant MARCH_31 = Instant.parse("2026-03-31T00:00:00Z");
    private static final Instant RECORDED = Instant.parse("2026-01-05T09:00:00.123456Z");

    private static EntityState account() {
        return new EntityState(new EntityId("e-1"), "Account", Map.of("number", Value.of("A-1")),
                EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), RECORDED, new Basis(2));
    }

    private static RelationshipState heldBy() {
        return new RelationshipState(new RelationshipId("r-1"), "HeldBy", new EntityId("e-1"), new EntityId("e-2"),
                Map.of(), EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), RECORDED, new Basis(2));
    }

    private static Row row(Object... namesAndCells) {
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (int i = 0; i < namesAndCells.length; i += 2) {
            cells.put((String) namesAndCells[i], (Cell) namesAndCells[i + 1]);
        }
        return new Row(cells);
    }

    private static Cell value(Value value) {
        return new Cell.ValueCell(value);
    }

    @Test
    void rowsAreWrittenWithTheirCoordinatesColumnsAndRowsOfCells() {
        QueryResult.Rows result = new QueryResult.Rows(MARCH_31, new Basis(7), List.of("a", "held", "n", "who", "none"),
                List.of(row("a", new Cell.EntityCell(account()), "held", new Cell.RelationshipCell(heldBy()),
                        "n", value(Value.of(3L)), "who", value(Value.of("ACME")), "none", Cell.empty())));

        Object tree = DocumentTrees.load(Queries.toJson(result));

        assertThat(DocumentTrees.keysAt(tree)).containsExactly("effectiveAt", "basis", "columns", "rows");
        assertThat(DocumentTrees.at(tree, "effectiveAt")).isEqualTo("2026-03-31T00:00:00Z");
        assertThat(DocumentTrees.at(tree, "basis")).isEqualTo(7);
        assertThat(DocumentTrees.listAt(tree, "columns")).containsExactly("a", "held", "n", "who", "none");
        List<Object> rows = DocumentTrees.listAt(tree, "rows");
        assertThat(rows).hasSize(1);
        List<Object> cells = DocumentTrees.listOf(rows.getFirst());
        assertThat(cells).hasSize(5);
        assertThat(DocumentTrees.keysOf(cells.get(0))).containsExactly("id", "entityType", "values", "period", "recordedAt",
                "recordedBasis");
        assertThat(DocumentTrees.keysOf(cells.get(1))).containsExactly("id", "relationshipType", "source", "target",
                "values", "period", "recordedAt", "recordedBasis");
        assertThat(cells.get(2)).isEqualTo(3);
        assertThat(cells.get(3)).isEqualTo("ACME");
        assertThat(cells.get(4)).as("an empty cell is null").isNull();
    }

    @Test
    void anEntityOrRelationshipCellIsWrittenAsPayloadsWriteTheState() {
        QueryResult.Rows result = new QueryResult.Rows(MARCH_31, new Basis(7), List.of("a", "held"),
                List.of(row("a", new Cell.EntityCell(account()), "held", new Cell.RelationshipCell(heldBy()))));

        Object tree = DocumentTrees.load(Queries.toJson(result));
        List<Object> cells = DocumentTrees.listOf(DocumentTrees.listAt(tree, "rows").getFirst());

        assertThat(DocumentTrees.toJson(cells.get(0))).isEqualTo(DocumentTrees.toJson(DocumentTrees.load(
                Payloads.toJson(account()))));
        assertThat(DocumentTrees.toJson(cells.get(1))).isEqualTo(DocumentTrees.toJson(DocumentTrees.load(
                Payloads.toJson(heldBy()))));
    }

    @Test
    void theStatesOfRowsListWhatTheyWithheld() {
        EntityState restricted = new EntityState(new EntityId("e-1"), "Account", Map.of("number", Value.of("A-1")),
                EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), RECORDED, new Basis(2),
                Set.of("balance"));
        RelationshipState share = new RelationshipState(new RelationshipId("r-1"), "Owns", new EntityId("e-1"),
                new EntityId("e-2"), Map.of(), EffectivePeriod.from(Instant.parse("2026-01-01T00:00:00Z")), RECORDED,
                new Basis(2), Set.of("share"));
        QueryResult.Rows result = new QueryResult.Rows(MARCH_31, new Basis(7), List.of("a", "held", "other"),
                List.of(row("a", new Cell.EntityCell(restricted), "held", new Cell.RelationshipCell(share),
                        "other", new Cell.EntityCell(account()))));

        Object tree = DocumentTrees.load(Queries.toJson(result));
        List<Object> cells = DocumentTrees.listOf(DocumentTrees.listAt(tree, "rows").getFirst());

        assertThat(DocumentTrees.at(cells.get(0), "withheld")).isEqualTo(List.of("balance"));
        assertThat(DocumentTrees.at(cells.get(1), "withheld")).isEqualTo(List.of("share"));
        assertThat(DocumentTrees.keysOf(cells.get(2))).doesNotContain("withheld");
    }

    @Test
    void everyValueTypeIsWrittenAsLiteralsAreWritten() {
        List<String> columns = List.of("string", "boolean", "int", "long", "decimal", "date", "instant");
        QueryResult.Rows result = new QueryResult.Rows(MARCH_31, new Basis(1), columns, List.of(row(
                "string", value(Value.of("x")), "boolean", value(Value.of(true)), "int", value(Value.of(5)),
                "long", value(Value.of(5_000_000_000L)), "decimal", value(Value.of(new BigDecimal("1200.50"))),
                "date", value(Value.of(LocalDate.of(2026, 3, 31))), "instant", value(Value.of(MARCH_31)))));

        String json = Queries.toJson(result);
        List<Object> cells = DocumentTrees.listOf(DocumentTrees.listAt(DocumentTrees.load(json), "rows").getFirst());

        assertThat(cells).hasSize(7).containsSubsequence("x", true, 5, 5_000_000_000L);
        assertThat(((Number) cells.get(4)).doubleValue()).isEqualTo(1200.5);
        assertThat(cells.subList(5, 7)).containsExactly("2026-03-31", "2026-03-31T00:00:00Z");
        assertThat(json).as("a decimal keeps its scale").contains("1200.50,");
    }

    @Test
    void aResultWithoutRowsIsWrittenWithTheColumns() {
        QueryResult.Rows result = new QueryResult.Rows(MARCH_31, new Basis(1), List.of("p"), List.of());

        Object tree = DocumentTrees.load(Queries.toJson(result));

        assertThat(DocumentTrees.listAt(tree, "columns")).containsExactly("p");
        assertThat(DocumentTrees.listAt(tree, "rows")).isEmpty();
        assertThat(Queries.toJson(result)).endsWith("\n").contains("\"rows\": []");
    }

    @Test
    void anInvalidResultIsWrittenAsItsProblems() {
        Problem problem = new Problem(ProblemCode.UNKNOWN_ATTRIBUTE, "@query.steps.0", "No such attribute.",
                Optional.of("one of balance"), Optional.of("balanse"),
                Optional.of("Did you mean 'balance'?"));
        QueryResult.Invalid result = new QueryResult.Invalid(List.of(problem));

        assertThat(Queries.toJson(result)).isEqualTo(Payloads.problemsToJson(List.of(problem)));
        assertThat(DocumentTrees.listAt(DocumentTrees.load(Queries.toJson(result)))).hasSize(1);
        assertThat(Queries.toJson(new QueryResult.Invalid(List.of()))).isEqualTo("[]\n");
    }

    @Test
    void theRowsOfAQueryRunAgainstAStoreAreWritten() {
        ReferenceDomainStore reference = new ReferenceDomainStore();
        Receipt parties = reference.replay(ReferenceStories.rd1Parties());
        reference.replay(ReferenceStories.rd5Accounts((EntityId) parties.temporaryIds().get(ReferenceStories.BETA),
                (EntityId) parties.temporaryIds().get(ReferenceStories.ACME)));
        QueryResult result = reference.store.view(Coordinates.effectiveAt(on(3, 31))).query(
                ReferenceQueries.totalBalanceByUltimateParent());

        Object tree = DocumentTrees.load(Queries.toJson(result));

        assertThat(DocumentTrees.listAt(tree, "columns")).containsExactly("party", "total");
        List<Object> rows = DocumentTrees.listAt(tree, "rows");
        assertThat(rows).hasSize(2);
        List<Object> first = DocumentTrees.listOf(rows.getFirst());
        assertThat(DocumentTrees.at(first.getFirst(), "entityType")).isEqualTo("Organization");
        assertThat(DocumentTrees.at(DocumentTrees.at(first.getFirst(), "values"), "name")).isEqualTo("ACME");
        assertThat(first.get(1)).isEqualTo(2000);
        assertThat(DocumentTrees.at(tree, "basis")).isEqualTo((int) reference.store.latestBasis().value());
    }
}
