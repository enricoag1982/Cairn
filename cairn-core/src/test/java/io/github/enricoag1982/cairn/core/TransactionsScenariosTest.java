package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code transactions} spec, one test each. */
class TransactionsScenariosTest {

    private static final Pattern UUID_V7 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final ReferenceStore reference = new ReferenceStore();
    private final Store store = reference.store;

    private static Transaction createsAcme() {
        return Transaction.by("importer").create(ACME, "Organization", from(1, 1), values("name", "ACME")).build();
    }

    // ------------------------------------------------------------------ committing a transaction

    @Test
    @DisplayName("Scenario: Creating several entities in one transaction")
    void creatingSeveralEntitiesInOneTransaction() {
        Receipt receipt = reference.createParties();

        assertThat(receipt.basis()).isEqualTo(new Basis(2));
        assertThat(receipt.transactionTime()).isBetween(on(1, 5), on(1, 6));
        assertThat(receipt.created()).hasSize(3).doesNotHaveDuplicates();
        assertThat(receipt.temporaryIds()).containsOnlyKeys(ACME, BETA, ALICE);
        assertThat(receipt.temporaryIds().values()).containsExactlyElementsOf(receipt.created());
        assertThat(reference.view(on(2, 1)).entities("Party")).hasSize(3);
    }

    @Test
    @DisplayName("Scenario: One invalid write rejects the transaction")
    void oneInvalidWriteRejectsTheTransaction() {
        Basis before = store.latestBasis();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values())
                .build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.MISSING_REQUIRED_VALUE, "$beta.name"));
        assertThat(reference.view(on(2, 1)).entities("Organization")).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(before);
        assertThat(store.transactions()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Data and schema share one sequence")
    void dataAndSchemaShareOneSequence() {
        Basis schemaBasis = store.latestBasis();
        var schemaTime = store.schema().history().getLast().transactionTime();

        Receipt data = reference.createParties();
        RegistrationResult.Applied later = (RegistrationResult.Applied) store.schema().register(
                new Origin("extra", "1"), SchemaDefinition.builder().entityType("Extra").build());

        assertThat(data.basis()).isGreaterThan(schemaBasis);
        assertThat(data.transactionTime()).isAfter(schemaTime);
        assertThat(later.basis()).isGreaterThan(data.basis());
        assertThat(later.transactionTime()).isAfter(data.transactionTime());
    }

    // ------------------------------------------------------------------ who and why

    @Test
    @DisplayName("Scenario: An agent acting for a person")
    void anAgentActingForAPerson() {
        Receipt receipt = reference.commit(Transaction.by("agent:onboarding").onBehalfOf("alice")
                .reason("New customer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        assertThat(store.transactions()).containsExactly(new TransactionRecord(receipt.basis(),
                receipt.transactionTime(), "agent:onboarding", Optional.of("alice"), Optional.of("New customer")));
        assertThat(receipt.principal()).isEqualTo("agent:onboarding");
        assertThat(receipt.onBehalfOf()).contains("alice");
        assertThat(receipt.reason()).contains("New customer");
    }

    @Test
    @DisplayName("Scenario: Missing principal")
    void missingPrincipal() {
        List<Problem> problems = reference.reject(Transaction.by("  ")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.MISSING_PRINCIPAL, "@transaction"));
        assertThat(store.transactions()).isEmpty();
    }

    // ------------------------------------------------------------------ temporary IDs

    @Test
    @DisplayName("Scenario: Referring to an entity created in the same transaction")
    void referringToAnEntityCreatedInTheSameTransaction() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("name", "ACME Holdings"))
                .build());

        EntityId acme = (EntityId) receipt.temporaryIds().get(ACME);
        assertThat(receipt.created()).containsExactly(acme);
        assertThat(reference.view(on(4, 1)).entity(acme)).get()
                .satisfies(state -> assertThat(state.values()).containsEntry("name", Value.of("ACME Holdings")));
        assertThat(reference.view(on(2, 1)).entity(acme)).get()
                .satisfies(state -> assertThat(state.values()).containsEntry("name", Value.of("ACME")));
    }

    @Test
    @DisplayName("Scenario: Unknown temporary ID")
    void unknownTemporaryId() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(BETA, from(3, 1), values("name", "Beta Ltd")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.UNKNOWN_TEMPORARY_ID, "$beta"));
    }

    // ------------------------------------------------------------------ entity IDs

    @Test
    @DisplayName("Scenario: Default IDs are UUIDv7")
    void defaultIdsAreUuidV7() {
        Store defaults = Stores.inMemory();
        defaults.schema().register(new Origin("things", "1"), SchemaDefinition.builder()
                .entityType("Thing", type -> type.attribute("name", ValueType.STRING)).build());

        CommitResult result = defaults.commit(Transaction.by("tester")
                .create("Thing", from(1, 1), values("name", "A thing")).build());

        Receipt receipt = ((CommitResult.Committed) result).receipt();
        assertThat(receipt.created()).singleElement().satisfies(created ->
                assertThat(created.value()).matches(UUID_V7));
    }

    @Test
    @DisplayName("Scenario: Supplying an ID that exists")
    void supplyingAnIdThatExists() {
        EntityId supplied = id(77);
        reference.commit(Transaction.by("importer")
                .create(supplied, "Organization", from(1, 1), values("name", "ACME")).build());

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(supplied, "Organization", from(1, 1), values("name", "Beta")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.ENTITY_EXISTS, supplied.value()));
    }

    @Test
    @DisplayName("Scenario: Deterministic IDs")
    void deterministicIds() {
        ReferenceStore other = new ReferenceStore();

        Receipt first = reference.createParties();
        Receipt second = other.createParties();

        assertThat(first.created()).containsExactly(id(1), id(2), id(3));
        assertThat(second.created()).isEqualTo(first.created());
        assertThat(second.temporaryIds()).isEqualTo(first.temporaryIds());
    }

    // ------------------------------------------------------------------ dry runs

    @Test
    @DisplayName("Scenario: Dry run of a valid transaction")
    void dryRunOfAValidTransaction() {
        Basis before = store.latestBasis();

        DryRunResult result = store.dryRun(createsAcme());

        assertThat(result).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reference.view(on(2, 1)).entities("Organization")).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(before);
        assertThat(store.transactions()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Dry run of an invalid transaction")
    void dryRunOfAnInvalidTransaction() {
        Transaction invalid = Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values()).build();

        DryRunResult dry = store.dryRun(invalid);
        List<Problem> committing = reference.reject(invalid);

        assertThat(dry).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) dry).problems()).isEqualTo(committing)
                .singleElement().satisfies(problem -> assertThat(problem.code())
                        .isEqualTo(ProblemCode.MISSING_REQUIRED_VALUE));
    }

    // ------------------------------------------------------------------ idempotency keys

    @Test
    @DisplayName("Scenario: Retrying a committed transaction")
    void retryingACommittedTransaction() {
        Transaction onboard = Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build();
        Receipt first = reference.commit(onboard);
        reference.clock.advance(Duration.ofMinutes(5));

        CommitResult second = store.commit(onboard);

        assertThat(second).isEqualTo(new CommitResult.Committed(first));
        assertThat(store.transactions()).hasSize(1);
        assertThat(store.latestBasis()).isEqualTo(first.basis());
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(1);
        assertThat(first.idempotencyKey()).contains("onboard-acme");
    }

    @Test
    @DisplayName("Scenario: Reusing a key for other writes")
    void reusingAKeyForOtherWrites() {
        reference.commit(Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        List<Problem> problems = reference.reject(Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(BETA, "Organization", from(1, 1), values("name", "Beta")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.IDEMPOTENCY_KEY_REUSED, "@transaction"));
        assertThat(store.transactions()).hasSize(1);
    }
}
