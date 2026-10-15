package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transactions that name entities by unique keys in the commit log and in the snapshot files of durable stores
 * (ADR-0022 and ADR-0026, design D4 of {@code add-unique-key-references}): the log holds the transaction as submitted,
 * so a store that reads it again keeps the keys, and the retry of a transaction with an idempotency key is recognized.
 */
class UniqueKeyDurabilityTest {

    private static final UniqueKey A1_NUMBER = new UniqueKey("Account", "number", Value.of("A-1"));
    private static final UniqueKey ACME_LEI = new UniqueKey("Organization", "lei", Value.of(LEI));

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

    /** What the tests keep: the receipts and IDs of a store that holds the accounts, the LEI and keyed writes. */
    private record Written(Transaction keyed, Receipt receipt, EntityId a1, EntityId acme) {
    }

    /**
     * Records RD-1, RD-5 and the LEI of ACME, then a transaction with an idempotency key that patches A-1's balance by
     * its number, relates ACME, by its LEI, to Beta and replaces ACME by its LEI, which is the transaction that the tests
     * retry.
     */
    private Written write(DirectoryStore store) {
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        Receipt accounts = durable.replay(store, ReferenceStories.rd5Accounts(entity(parties, BETA), acme));
        durable.replay(store, ReferenceStories.rd9AcmeLei(acme));
        durable.clock.moveTo(on(5, 20));
        Transaction keyed = Transaction.by("importer").idempotencyKey("keys-1")
                .patch(A1_NUMBER, from(3, 15), values("balance", money("1500")))
                .relate("Owns", ACME_LEI, entity(parties, BETA), from(2, 1), values("share", 60))
                .replace(ACME_LEI, from(6, 1), values("name", "ACME Holdings", "lei", LEI))
                .build();
        Receipt receipt = DurableFixture.commit(store, keyed);
        return new Written(keyed, receipt, entity(accounts, A1), acme);
    }

    private void assertKeysStillWork(DirectoryStore reopened, Written written) {
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 31))).entity(A1_NUMBER).orElseThrow().values())
                .containsEntry("balance", Value.of(money("1500")));
        assertThat(reopened.view(Coordinates.effectiveAt(on(7, 1))).entity(written.acme()).orElseThrow().values())
                .as("replaced by the key").containsEntry("name", Value.of("ACME Holdings"));
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 1))).entity(ACME_LEI).orElseThrow().id())
                .isEqualTo(written.acme());
        Basis before = reopened.latestBasis();

        CommitResult retry = reopened.commit(written.keyed());

        assertThat(retry).as("the transaction was recorded as submitted, with its keys")
                .isEqualTo(new CommitResult.Committed(written.receipt()));
        assertThat(reopened.latestBasis()).isEqualTo(before);
        Transaction changed = Transaction.by("importer").idempotencyKey("keys-1")
                .patch(new UniqueKey("Account", "number", Value.of("A-2")), from(3, 15),
                        values("balance", money("1500")))
                .build();
        CommitResult reused = reopened.commit(changed);
        assertThat(reused).isInstanceOf(CommitResult.Rejected.class);
        List<Problem> problems = ((CommitResult.Rejected) reused).problems();
        assertThat(problems).singleElement().satisfies(problem ->
                assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
        Receipt next = DurableFixture.commit(reopened, Transaction.by("importer")
                .patch(new UniqueKey("Account", "number", Value.of("A-2")), from(3, 15),
                        values("balance", money("2500"))).build());
        assertThat(next.basis()).isGreaterThan(before);
    }

    @Test
    void aStoreThatWasClosedKeepsTheKeysOfItsTransactionsAndRecognizesARetry() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first);
        first.close();

        assertKeysStillWork(durable.open(), written);
    }

    @Test
    void aStoreThatWasRecoveredFromTheLogAloneKeepsTheKeysToo() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first);
        DurableFixture.crash(first);

        assertKeysStillWork(durable.open(), written);
    }

    @Test
    void aStoreReopenedTwiceKeepsThemThroughTheSnapshotItWroteWhileRecovering() {
        DirectoryStore first = durable.openWithReferenceModel();
        Written written = write(first);
        DurableFixture.crash(first);
        DirectoryStore second = durable.open();
        second.close();

        assertKeysStillWork(durable.open(), written);
    }

    @Test
    void aKeyThatNamesNothingIsRejectedByAStoreThatWasReopenedAsWellAsByTheOneThatWasNot() {
        DirectoryStore first = durable.openWithReferenceModel();
        write(first);
        Consumer<DirectoryStore> check = store -> {
            CommitResult result = store.commit(Transaction.by("importer")
                    .patch(new UniqueKey("Account", "number", Value.of("A-9")), from(3, 15),
                            values("balance", money("1"))).build());
            assertThat(result).isInstanceOf(CommitResult.Rejected.class);
            assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_KEY_NOT_FOUND);
                assertThat(problem.path()).isEqualTo("@write.0");
            });
        };
        check.accept(first);
        first.close();

        check.accept(durable.open());
    }
}
