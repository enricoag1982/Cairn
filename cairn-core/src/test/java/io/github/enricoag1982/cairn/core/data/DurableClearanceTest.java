package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classifications and clearances in a store kept in a directory, recovered from the commit log alone: the log keeps the
 * classifications of the schema, and the clearance of a transaction with an idempotency key, so that a retry after a crash
 * is recognized as the retry of that transaction and of no other (ADR-0036, design D1 and D4 of
 * {@code add-attribute-access-control}). The same after a clean close, and in RocksDB stores, is shown in
 * {@code AttributeAccessDurabilityTest}.
 */
class DurableClearanceTest {

    @TempDir
    Path directory;

    private DurableFixture durable;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    private static Transaction rename(EntityId alice, Clearance clearance) {
        Transaction.Builder builder = Transaction.by(PRINCIPAL).idempotencyKey("rename-alice");
        if (clearance != null) {
            builder.clearance(clearance);
        }
        return builder.patch(alice, from(4, 1), values("name", "Alice Smith")).build();
    }

    @Test
    void aStoreRecoveredFromTheLogAloneKnowsTheClassificationsAndTheClearanceOfAKeyedTransaction() {
        DirectoryStore store = durable.open();
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), Definitions.withClassification(
                ReferenceModel.definition(), "Account", "balance", "finance")))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        durable.replay(store, ReferenceStories.rd5Accounts(parties.temporaryIds().get(BETA),
                parties.temporaryIds().get(ACME)));
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        durable.clock.moveTo(on(4, 2));
        Receipt receipt = DurableFixture.commit(store, rename(alice, Clearance.of("finance")));
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(StoreFiles.snapshots(directory)).as("the log alone recovers the store").isEmpty();
        assertThat(recovered.schema().current().entityType("Account").orElseThrow().attribute("balance").orElseThrow()
                .classification()).hasValue("finance");
        assertThat(recovered.commit(rename(alice, Clearance.of("finance"))))
                .isEqualTo(new CommitResult.Committed(receipt));
        for (Clearance other : new Clearance[] {Clearance.none(), Clearance.of("identity"), null}) {
            CommitResult retry = recovered.commit(rename(alice, other));
            assertThat(retry).isInstanceOf(CommitResult.Rejected.class);
            assertThat(((CommitResult.Rejected) retry).problems()).singleElement()
                    .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
        }
    }

    @Test
    void aClearanceThatCoversNothingIsRecoveredFromTheLogAsSuch() {
        DirectoryStore store = durable.openWithReferenceModel();
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        EntityId alice = (EntityId) parties.temporaryIds().get(ALICE);
        durable.clock.moveTo(on(4, 2));
        Receipt receipt = DurableFixture.commit(store, rename(alice, Clearance.none()));
        DurableFixture.crash(store);

        DirectoryStore recovered = durable.open();

        assertThat(recovered.commit(rename(alice, Clearance.none()))).isEqualTo(new CommitResult.Committed(receipt));
        assertThat(recovered.commit(rename(alice, null))).isInstanceOf(CommitResult.Rejected.class);
    }
}
