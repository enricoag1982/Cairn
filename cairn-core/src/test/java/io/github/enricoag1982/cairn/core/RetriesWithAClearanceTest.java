package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A transaction with a clearance whose writes the clearance does not allow is refused before its idempotency key is
 * compared (ADR-0036, design D4 of {@code add-attribute-access-control}), judged by the latest schema: a retry of a
 * transaction that was written when an attribute was not classified cannot confirm, by getting the receipt of the first
 * submission, a value that has been classified since. A retry whose writes are still allowed is the retry it was. The
 * tests run against RocksDB stores too.
 */
class RetriesWithAClearanceTest {

    private static final Clearance NONE = Clearance.none();
    private static final Clearance FINANCE = Clearance.of("finance");

    private static SchemaDefinition finance() {
        return withClassification(ReferenceModel.definition(), "Account", "balance", "finance");
    }

    private static SchemaDefinition identity() {
        return withClassification(ReferenceModel.definition(), "Organization", "lei", "identity");
    }

    private static void classify(ReferenceStore reference, SchemaDefinition definition) {
        reference.recordedOn(on(6, 1));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), definition))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    private static Transaction fund(EntityId a1, Clearance clearance) {
        return Transaction.by(PRINCIPAL).idempotencyKey("fund-a1").clearance(clearance)
                .patch(a1, from(5, 1), values("balance", money("1500"))).build();
    }

    private static List<Problem> rejected(CommitResult result) {
        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    @Test
    void aRetryCannotConfirmAValueThatHasBeenClassifiedSince() {
        ReferenceStore reference = new ReferenceStore();
        EntityId a1 = entity(reference.createAccounts(), A1);
        reference.recordedOn(on(5, 2));
        Transaction fund = fund(a1, NONE);
        Receipt first = reference.commit(fund);
        classify(reference, finance());

        List<Problem> committed = rejected(reference.store.commit(fund));
        DryRunResult dryRun = reference.store.dryRun(fund);

        assertThat(committed).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
            assertThat(problem.path()).isEqualTo(a1.value() + ".balance");
        });
        assertThat(dryRun).isInstanceOfSatisfying(DryRunResult.Rejected.class,
                rejected -> assertThat(rejected.problems()).isEqualTo(committed));
        assertThat(first.idempotencyKey()).contains("fund-a1");
        assertThat(reference.store.transactions()).as("nothing more was recorded").hasSize(3);
    }

    @Test
    void aRetryWhoseWritesTheClearanceStillAllowsGetsTheEarlierReceipt() {
        ReferenceStore reference = ReferenceStore.with(finance());
        EntityId a1 = entity(reference.createAccounts(), A1);
        reference.recordedOn(on(5, 2));
        Transaction fund = fund(a1, FINANCE);
        Receipt first = reference.commit(fund);

        assertThat(reference.store.commit(fund)).isEqualTo(new CommitResult.Committed(first));
        assertThat(reference.store.dryRun(fund)).isInstanceOf(DryRunResult.WouldCommit.class);
    }

    @Test
    void aRetryWithoutAClearanceIsNotRefused() {
        ReferenceStore reference = new ReferenceStore();
        EntityId a1 = entity(reference.createAccounts(), A1);
        reference.recordedOn(on(5, 2));
        Transaction fund = fund(a1, NONE);
        Transaction noClearance = Transaction.by(PRINCIPAL).idempotencyKey("fund-b1")
                .patch(a1, from(5, 1), values("balance", money("1500"))).build();
        reference.commit(fund);
        Receipt first = reference.commit(noClearance);
        classify(reference, finance());

        assertThat(reference.store.commit(noClearance)).as("the transaction has no clearance, so it is no one's to refuse")
                .isEqualTo(new CommitResult.Committed(first));
    }

    @Test
    void aRetryThatNamesAnEntityByAKeyClassifiedSinceIsRefusedAtTheReference() {
        ReferenceStore reference = new ReferenceStore();
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(5, 2));
        Transaction rename = Transaction.by(PRINCIPAL).idempotencyKey("rename-acme").clearance(NONE)
                .patch(new UniqueKey("Organization", "lei", Value.of(LEI)), from(5, 1),
                        values("name", "ACME Holdings")).build();
        reference.commit(rename);
        classify(reference, identity());

        List<Problem> committed = rejected(reference.store.commit(rename));

        assertThat(committed).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
            assertThat(problem.path()).isEqualTo("@write.0");
        });
        assertThat(reference.store.dryRun(rename)).isInstanceOf(DryRunResult.Rejected.class);
    }

    @Test
    void aRetryWithAnotherContentIsStillAKeyUsedWithDifferentContent() {
        ReferenceStore reference = new ReferenceStore();
        EntityId a1 = entity(reference.createAccounts(), A1);
        reference.recordedOn(on(5, 2));
        reference.commit(fund(a1, FINANCE));

        List<Problem> problems = rejected(reference.store.commit(fund(a1, NONE)));

        assertThat(problems).singleElement().satisfies(problem ->
                assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
    }
}
