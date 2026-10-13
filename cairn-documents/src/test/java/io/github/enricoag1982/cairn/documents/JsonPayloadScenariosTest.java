package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code json-payloads} spec that concern reading transaction payloads and locating their
 * problems, one test each, in the reference domain.
 */
class JsonPayloadScenariosTest {

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    // ------------------------------------------------------------------ transaction payloads

    @Test
    @DisplayName("Scenario: Creating organizations from a payload")
    void creatingOrganizationsFromAPayload() {
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "temporaryId": "acme", "entityType": "Organization",
                                  "from": "2026-01-01T00:00:00Z", "values": { "name": "ACME" } } },
                    { "create": { "temporaryId": "beta", "entityType": "Organization",
                                  "from": "2026-01-01T00:00:00Z", "values": { "name": "Beta" } } }
                  ]
                }
                """;

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());
        CommitResult result = reference.store.commit(read.transaction());

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        Receipt receipt = ((CommitResult.Committed) result).receipt();
        assertThat(receipt.principal()).isEqualTo("agent:onboarding");
        assertThat(receipt.temporaryIds()).containsOnlyKeys(new TemporaryId("acme"), new TemporaryId("beta"));
        assertThat(receipt.temporaryIds().values()).allSatisfy(id -> assertThat(id).isInstanceOf(EntityId.class));
        assertThat(receipt.temporaryIds().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Scenario: A date value")
    void aDateValue() {
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "entityType": "Person", "from": "2026-01-01T00:00:00Z",
                                  "values": { "name": "Alice", "birthDate": "1990-05-17" } } }
                  ]
                }
                """;

        TransactionPayload read = PayloadFixtures.read(payload, reference.schema());

        assertThat(read.transaction().writes()).singleElement().isInstanceOfSatisfying(Write.Create.class,
                create -> assertThat(create.values()).containsEntry("birthDate", Value.of(LocalDate.of(1990, 5, 17)))
                        .isEqualTo(Map.of("name", Value.of("Alice"), "birthDate", Value.of(LocalDate.of(1990, 5, 17)))));
    }

    @Test
    @DisplayName("Scenario: Unknown write kind")
    void unknownWriteKind() {
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "entityType": "Organization", "from": "2026-01-01T00:00:00Z",
                                  "values": { "name": "ACME" } } },
                    { "upsert": { "entityType": "Organization", "from": "2026-01-01T00:00:00Z",
                                  "values": { "name": "Beta" } } }
                  ]
                }
                """;

        PayloadReadResult result = Payloads.readTransaction(payload, reference.schema());

        assertThat(result).isInstanceOf(PayloadReadResult.Invalid.class);
        List<Problem> problems = ((PayloadReadResult.Invalid) result).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
            assertThat(problem.found()).contains("upsert");
            assertThat(problem.location()).contains(locationOf(payload, "{ \"upsert\"", "/writes/1"));
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint)
                    .contains("create", "relate", "patch", "replace", "endDate"));
        });
    }

    // ------------------------------------------------------------------ problems are located

    @Test
    @DisplayName("Scenario: Locating a value problem")
    void locatingAValueProblem() {
        // An account has exactly one holder, so the payload also relates account A-1 to Beta.
        String payload = """
                {
                  "cairn": 1,
                  "principal": "agent:onboarding",
                  "writes": [
                    { "create": { "temporaryId": "beta", "entityType": "Organization",
                                  "from": "2026-01-01T00:00:00Z", "values": { "name": "Beta" } } },
                    { "create": { "temporaryId": "a1", "entityType": "Account", "from": "2026-01-01T00:00:00Z",
                                  "values": { "number": "A-1", "currency": "eur", "balance": 1000 } } },
                    { "relate": { "relationshipType": "HeldBy", "source": "$a1", "target": "$beta",
                                  "from": "2026-01-01T00:00:00Z" } }
                  ]
                }
                """;
        TransactionPayload read = PayloadFixtures.read(payload, reference.schema(), reference.lookup());

        DryRunResult result = reference.store.dryRun(read.transaction());

        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        List<Problem> located = read.locate(((DryRunResult.Rejected) result).problems());
        assertThat(located).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.VALUE_PATTERN_MISMATCH);
            assertThat(problem.path()).isEqualTo("$a1.currency");
            assertThat(problem.location()).contains(locationOf(payload, "\"currency\"",
                    "/writes/1/create/values/currency"));
        });
    }
}
