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
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Design D7: committing, dry runs, idempotency keys and the transaction history. */
class StoreCommitTest {

    private final ReferenceStore reference = new ReferenceStore();

    private static Transaction.Builder createsAcme() {
        return Transaction.by("importer").create(ACME, "Organization", from(1, 1), values("name", "ACME"));
    }

    private static Transaction.Builder createsInvalid() {
        return Transaction.by("importer").create(ACME, "Organization", from(1, 1), values());
    }

    // ------------------------------------------------------------------ receipts

    @Test
    void theReceiptRepeatsWhoAndWhyAndTheKey() {
        Receipt receipt = reference.commit(createsAcme().onBehalfOf("alice").reason("New customer")
                .idempotencyKey("k1").build());

        assertThat(receipt.principal()).isEqualTo("importer");
        assertThat(receipt.onBehalfOf()).contains("alice");
        assertThat(receipt.reason()).contains("New customer");
        assertThat(receipt.idempotencyKey()).contains("k1");
    }

    @Test
    void theReceiptOfATransactionWithoutOptionalPartsHasNone() {
        Receipt receipt = reference.commit(createsAcme().build());

        assertThat(receipt.onBehalfOf()).isEmpty();
        assertThat(receipt.reason()).isEmpty();
        assertThat(receipt.idempotencyKey()).isEmpty();
    }

    @Test
    void theReceiptListsTemporaryAndCreatedIdsInTheOrderOfTheWrites() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .create(BETA, "Organization", from(1, 1), values("name", "Beta"))
                .create("Organization", from(1, 1), values("name", "Unnamed"))
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        assertThat(receipt.created()).containsExactly(id(1), id(2), id(3), id(4));
        assertThat(receipt.temporaryIds()).containsExactly(
                java.util.Map.entry(BETA, id(1)), java.util.Map.entry(ACME, id(3)),
                java.util.Map.entry(ALICE, id(4)));
    }

    @Test
    void aTransactionThatCreatesNothingHasAnEmptyListOfCreatedIds() {
        EntityId acme = (EntityId) reference.commit(createsAcme().build()).temporaryIds().get(ACME);

        Receipt receipt = reference.commit(Transaction.by("importer").endDate(acme, on(6, 1)).build());

        assertThat(receipt.created()).isEmpty();
        assertThat(receipt.temporaryIds()).isEmpty();
    }

    @Test
    void anEmptyTransactionIsCommittedWithABasis() {
        Basis before = reference.store.latestBasis();

        Receipt receipt = reference.commit(Transaction.by("importer").build());

        assertThat(receipt.basis().value()).isEqualTo(before.value() + 1);
        assertThat(reference.store.transactions()).hasSize(1);
    }

    // ------------------------------------------------------------------ basis and transaction time

    @Test
    void everyCommitGetsAHigherBasisAndALaterTransactionTime() {
        Receipt first = reference.commit(createsAcme().build());
        Receipt second = reference.commit(Transaction.by("importer").build());
        reference.clock.advance(Duration.ofDays(1));
        Receipt third = reference.commit(Transaction.by("importer").build());

        assertThat(first.basis()).isEqualTo(new Basis(2));
        assertThat(second.basis()).isEqualTo(new Basis(3));
        assertThat(third.basis()).isEqualTo(new Basis(4));
        assertThat(second.transactionTime()).isEqualTo(first.transactionTime().plus(1, ChronoUnit.MICROS));
        assertThat(third.transactionTime()).isAfter(second.transactionTime());
        assertThat(third.transactionTime()).isEqualTo(TestClock.START.plus(Duration.ofDays(1))
                .truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void theBasisAndTheTransactionTimeAreThoseOfTheHistory() {
        Receipt receipt = reference.commit(createsAcme().build());

        assertThat(reference.store.latestBasis()).isEqualTo(receipt.basis());
        assertThat(reference.store.transactions()).singleElement().satisfies(record -> {
            assertThat(record.basis()).isEqualTo(receipt.basis());
            assertThat(record.transactionTime()).isEqualTo(receipt.transactionTime());
        });
    }

    // ------------------------------------------------------------------ generated and supplied IDs

    @Test
    void generatedIdsSkipIdsThatCallersSupplied() {
        reference.commit(Transaction.by("importer")
                .create(id(1), "Organization", from(1, 1), values("name", "Supplied"))
                .create(id(3), "Organization", from(1, 1), values("name", "Supplied too")).build());

        Receipt receipt = reference.commit(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "A"))
                .create("Organization", from(1, 1), values("name", "B"))
                .create("Organization", from(1, 1), values("name", "C")).build());

        assertThat(receipt.created()).containsExactly(id(2), id(4), id(5));
    }

    @Test
    void aSuppliedIdThatAnEarlierWriteOfTheTransactionDrewIsTaken() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "A"))
                .create(id(1), "Organization", from(1, 1), values("name", "B")).build());

        assertProblem(problems.getFirst(), ProblemCode.ENTITY_EXISTS, id(1).value());
    }

    // ------------------------------------------------------------------ dry runs

    @Test
    void aDryRunReportsTheProblemsThatCommittingReports() {
        Transaction invalid = Transaction.by("importer")
                .create(ACME, "Organisation", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values("nmae", "Beta"))
                .patch(id(88), from(1, 1), values("name", "x")).build();

        DryRunResult dry = reference.store.dryRun(invalid);
        List<Problem> committing = reference.reject(invalid);

        assertThat(dry).isEqualTo(new DryRunResult.Rejected(committing));
        assertThat(committing).hasSize(3);
    }

    @Test
    void aDryRunUsesNoBasisAndNoTransactionTime() {
        reference.commit(createsAcme().build());
        Basis before = reference.store.latestBasis();

        assertThat(reference.store.dryRun(Transaction.by("importer").build())).isInstanceOf(DryRunResult.WouldCommit.class);
        reference.store.dryRun(createsInvalid().build());
        Receipt next = reference.commit(Transaction.by("importer").build());

        assertThat(reference.store.latestBasis()).isEqualTo(next.basis());
        assertThat(next.basis().value()).isEqualTo(before.value() + 1);
        assertThat(next.transactionTime()).isEqualTo(reference.store.transactions().getFirst().transactionTime()
                .plus(1, ChronoUnit.MICROS));
    }

    @Test
    void aDryRunReservesNoId() {
        Transaction twoEntities = Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "A"))
                .create("Organization", from(1, 1), values("name", "B")).build();

        reference.store.dryRun(twoEntities);
        reference.store.dryRun(twoEntities);
        Receipt receipt = reference.commit(twoEntities);

        assertThat(receipt.created()).containsExactly(id(1), id(2));
    }

    @Test
    void aRejectedCommitReservesNoId() {
        reference.reject(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "A"))
                .create("Organization", from(1, 1), values()).build());

        Receipt receipt = reference.commit(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "A")).build());

        assertThat(receipt.created()).containsExactly(id(1));
    }

    @Test
    void aDryRunChangesNothingThatAViewCanSee() {
        EntityId acme = (EntityId) reference.commit(createsAcme().build()).temporaryIds().get(ACME);
        var before = reference.view(on(2, 1));

        reference.store.dryRun(Transaction.by("importer")
                .patch(acme, from(1, 1), values("name", "ACME Holdings"))
                .create("Organization", from(1, 1), values("name", "B")).build());

        assertThat(reference.view(on(2, 1)).entities("Party")).isEqualTo(before.entities("Party"));
        assertThat(reference.view(on(2, 1)).basis()).isEqualTo(before.basis());
        assertThat(reference.store.transactions()).hasSize(1);
    }

    @Test
    void aDryRunDoesNotUseUpAnIdempotencyKey() {
        Transaction keyed = createsAcme().idempotencyKey("k1").build();

        assertThat(reference.store.dryRun(keyed)).isInstanceOf(DryRunResult.WouldCommit.class);
        Receipt receipt = reference.commit(keyed);

        assertThat(receipt.idempotencyKey()).contains("k1");
        assertThat(reference.store.transactions()).hasSize(1);
    }

    @Test
    void aDryRunOfAUsedKeyReportsWhatCommittingWould() {
        Transaction keyed = createsAcme().idempotencyKey("k1").build();
        reference.commit(keyed);

        DryRunResult same = reference.store.dryRun(keyed);
        DryRunResult other = reference.store.dryRun(createsAcme().idempotencyKey("k1").reason("other").build());

        assertThat(same).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(other).isInstanceOf(DryRunResult.Rejected.class);
        assertProblem(((DryRunResult.Rejected) other).problems().getFirst(), ProblemCode.IDEMPOTENCY_KEY_REUSED,
                "@transaction");
        assertThat(reference.store.transactions()).hasSize(1);
    }

    // ------------------------------------------------------------------ idempotency keys

    @Test
    void aRetryAfterOtherCommitsStillGetsTheFirstReceipt() {
        Transaction keyed = createsAcme().idempotencyKey("k1").build();
        Receipt first = reference.commit(keyed);
        reference.commit(Transaction.by("other").build());
        reference.store.schema().register(new Origin("extra", "1"), SchemaDefinition.builder().entityType("X").build());

        CommitResult retry = reference.store.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).idempotencyKey("k1").build());

        assertThat(retry).isEqualTo(new CommitResult.Committed(first));
        assertThat(reference.store.transactions()).hasSize(2);
    }

    @Test
    void aRetryThatDiffersInAnyPartIsRejected() {
        reference.commit(createsAcme().onBehalfOf("alice").reason("r").idempotencyKey("k1").build());
        List<Transaction> others = List.of(
                createsAcme().onBehalfOf("alice").reason("r").idempotencyKey("k1")
                        .create(BETA, "Organization", from(1, 1), values("name", "Beta")).build(),
                Transaction.by("someone else").create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                        .onBehalfOf("alice").reason("r").idempotencyKey("k1").build(),
                createsAcme().reason("r").idempotencyKey("k1").build(),
                createsAcme().onBehalfOf("alice").idempotencyKey("k1").build());

        for (Transaction other : others) {
            assertProblem(reference.reject(other).getFirst(), ProblemCode.IDEMPOTENCY_KEY_REUSED, "@transaction");
        }
        assertThat(reference.store.commit(createsAcme().onBehalfOf("alice").reason("r").idempotencyKey("k1").build()))
                .as("an equal transaction built again").isInstanceOf(CommitResult.Committed.class);
        assertThat(reference.store.transactions()).hasSize(1);
    }

    @Test
    void aReusedKeyIsTheOnlyProblemEvenIfTheOtherWritesAreInvalidToo() {
        reference.commit(createsAcme().idempotencyKey("k1").build());
        Transaction invalid = createsInvalid().idempotencyKey("k1").build();

        List<Problem> committing = reference.reject(invalid);
        DryRunResult dry = reference.store.dryRun(invalid);

        assertThat(committing).singleElement().satisfies(problem ->
                assertProblem(problem, ProblemCode.IDEMPOTENCY_KEY_REUSED, "@transaction"));
        assertThat(dry).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) dry).problems()).isEqualTo(committing);
    }

    @Test
    void aRejectedTransactionDoesNotClaimItsKey() {
        reference.reject(createsInvalid().idempotencyKey("k1").build());

        Receipt receipt = reference.commit(createsAcme().idempotencyKey("k1").build());

        assertThat(receipt.idempotencyKey()).contains("k1");
    }

    @Test
    void differentKeysAreDifferentTransactions() {
        reference.commit(createsAcme().idempotencyKey("k1").build());

        Receipt second = reference.commit(Transaction.by("importer")
                .create(BETA, "Organization", from(1, 1), values("name", "Beta")).idempotencyKey("k2").build());

        assertThat(second.created()).containsExactly(id(2));
        assertThat(reference.store.transactions()).hasSize(2);
    }

    @Test
    void transactionsWithoutAKeyAreNeverRetries() {
        Transaction plain = createsAcme().build();

        Receipt first = reference.commit(plain);
        Receipt second = reference.commit(plain);

        assertThat(second.basis()).isGreaterThan(first.basis());
        assertThat(second.created()).isNotEqualTo(first.created());
    }

    @Test
    void aRetryReturnsTheFirstReceiptEvenIfItWouldBeInvalidNow() {
        Transaction supplied = Transaction.by("importer").idempotencyKey("k1")
                .create(id(5), "Organization", from(1, 1), values("name", "ACME")).build();
        Receipt first = reference.commit(supplied);

        CommitResult retry = reference.store.commit(supplied);

        assertThat(retry).isEqualTo(new CommitResult.Committed(first));
    }

    // ------------------------------------------------------------------ the transaction history

    @Test
    void theHistoryListsDataTransactionsInOrderWithWhoAndWhy() {
        Receipt first = reference.commit(createsAcme().onBehalfOf("alice").reason("New customer").build());
        reference.store.schema().register(new Origin("extra", "1"), SchemaDefinition.builder().entityType("X").build());
        Receipt second = reference.commit(Transaction.by("agent:cleanup").build());

        assertThat(reference.store.transactions()).containsExactly(
                new TransactionRecord(first.basis(), first.transactionTime(), "importer", Optional.of("alice"),
                        Optional.of("New customer")),
                new TransactionRecord(second.basis(), second.transactionTime(), "agent:cleanup", Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void rejectedTransactionsAreNotInTheHistory() {
        reference.reject(createsInvalid().build());
        reference.reject(Transaction.by("").build());

        assertThat(reference.store.transactions()).isEmpty();
    }

    @Test
    void theHistoryCannotBeChangedByTheCaller() {
        reference.commit(createsAcme().build());
        List<TransactionRecord> history = reference.store.transactions();

        reference.commit(Transaction.by("importer").build());

        assertThat(history).hasSize(1);
        assertThat(reference.store.transactions()).hasSize(2);
    }

    @Test
    void transactionTimesHaveMicrosecondPrecision() {
        Receipt receipt = reference.commit(createsAcme().build());

        assertThat(receipt.transactionTime()).isEqualTo(receipt.transactionTime().truncatedTo(ChronoUnit.MICROS));
        assertThat(receipt.transactionTime()).isBefore(Instant.parse("2026-01-06T00:00:00Z"));
    }
}
