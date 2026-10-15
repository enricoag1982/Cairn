package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.relationship;
import static io.github.enricoag1982.cairn.fixtures.Definitions.changeEntityType;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.RecordingSubscriber;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.RecordingKeyValueStore;
import io.github.enricoag1982.cairn.core.storage.LedgerCodec;
import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.Changed;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.PurgeChange;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.SchemaChange;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionChange;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What a store reads as its changes (ADR-0029, design D3 and D4 of {@code add-change-feed}), beyond the scenarios: the
 * merge of the index with the transaction records, the schema history and the purge records, the names of types at the
 * basis of a change, the order of what a change lists, reading without a lock, a purge that removes the entries of what
 * it removes at every basis, and a store that lacks a record.
 */
class StoreChangesTest {

    private final RecordingKeyValueStore keys = new RecordingKeyValueStore();
    private final TestClock clock = new TestClock();
    private final InMemoryStore store = new InMemoryStore(keys, clock, IdGenerators.deterministic(),
            ReferenceModel.executableRules(), ReferenceModel.declaredRules());

    private StoreChangesTest registerReferenceModel() {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        return this;
    }

    private static TransactionChange transaction(Change change) {
        assertThat(change).isInstanceOf(TransactionChange.class);
        return (TransactionChange) change;
    }

    private Receipt commit(Transaction transaction) {
        CommitResult result = store.commit(transaction);
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    // ------------------------------------------------------------------ the merge

    @Test
    void aStoreWithoutAnythingHasNoChanges() {
        assertThat(store.changes(Basis.NONE, 10)).isEmpty();
    }

    @Test
    void everyKindOfBasisHasItsChangeInTheOrderOfTheBases() {
        registerReferenceModel();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        assertThat(store.schema().register(new Origin("extras", "1.0.0"), SchemaDefinition.builder()
                .entityType("Extra", extra -> extra.attribute("note", ValueType.STRING)).build()))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt employment = ReferenceStories.replay(store, clock,
                ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        PurgeResult purged = store.purge(ReferenceStories.rd11PurgeAlice(entity(parties, ALICE)));
        assertThat(purged).isInstanceOf(PurgeResult.Purged.class);

        List<Change> changes = store.changes(Basis.NONE, 100);

        assertThat(changes).extracting(Change::basis).extracting(Basis::value).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(changes).extracting(change -> change.getClass().getSimpleName()).containsExactly("SchemaChange",
                "TransactionChange", "SchemaChange", "TransactionChange", "PurgeChange");
        assertThat(((SchemaChange) changes.get(2)).registration().origin()).isEqualTo(new Origin("extras", "1.0.0"));
        assertThat(changes.get(3).transactionTime()).isEqualTo(employment.transactionTime());
        assertThat(((PurgeChange) changes.getLast()).purge()).isEqualTo(((PurgeResult.Purged) purged).record());
        assertThat(store.changes(new Basis(2), 2)).extracting(Change::basis).extracting(Basis::value)
                .containsExactly(3L, 4L);
    }

    @Test
    void aRegistrationThatChangesNothingAndATransactionThatIsRejectedLeaveNoChange() {
        registerReferenceModel();
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Unchanged.class);
        assertThat(store.commit(Transaction.by(PRINCIPAL).create("Person", from(1, 1), values("name", 5)).build()))
                .isInstanceOf(CommitResult.Rejected.class);

        assertThat(store.changes(Basis.NONE, 10)).singleElement().isInstanceOf(SchemaChange.class);
        assertThat(store.latestBasis()).isEqualTo(new Basis(1));
    }

    @Test
    void aTransactionThatChangedNothingIsOnRecordWithNothingListed() {
        registerReferenceModel();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        Receipt same = commit(Transaction.by(PRINCIPAL).reason("Nothing to change")
                .patch(entity(parties, ALICE), from(1, 1), values("name", "Alice")).build());

        TransactionChange change = transaction(store.changes(parties.basis(), 1).getFirst());

        assertThat(change.basis()).isEqualTo(same.basis());
        assertThat(change.transaction().reason()).contains("Nothing to change");
        assertThat(change.changed()).isEmpty();
    }

    @Test
    void aBasisWithoutARecordIsAnEngineBugAndIsReportedNamingTheBasis() {
        registerReferenceModel();
        ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        store.records().removeLast();

        assertThatThrownBy(() -> store.changes(Basis.NONE, 10)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basis 2");
        assertThat(store.changes(Basis.NONE, 1)).as("the bases before it can still be read").hasSize(1);
    }

    // ------------------------------------------------------------------ names and order

    @Test
    void aChangeNamesTheTypeAsItWasNamedAtItsBasis() {
        registerReferenceModel();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        Receipt accounts = ReferenceStories.replay(store, clock,
                ReferenceStories.rd5Accounts(entity(parties, BETA), entity(parties, ACME)));
        EntityId a1 = entity(accounts, A1);
        SchemaDefinition renamed = changeEntityType(ReferenceModel.definition(), "Account", type ->
                new EntityTypeDefinition("Ledger", type.isAbstract(), type.supertype(), type.traits(),
                        type.attributes(), List.of("Account"), type.deprecated(), type.description(), type.rules()));
        assertThat(store.schema().register(ReferenceModel.origin("1.1.0"), renamed))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt patch = ReferenceStories.replay(store, clock, ReferenceStories.rd5BalancePatch(a1));

        TransactionChange before = transaction(store.changes(new Basis(parties.basis().value() - 1), 1).getFirst());
        TransactionChange creation = transaction(store.changes(parties.basis(), 1).getFirst());
        TransactionChange after = transaction(store.changes(new Basis(patch.basis().value() - 1), 1).getFirst());

        assertThat(before.changed()).extracting(Changed::type).containsExactly("Organization", "Organization",
                "Person");
        assertThat(creation.basis()).isEqualTo(accounts.basis());
        assertThat(creation.changed()).extracting(Changed::type).as("as Account was named when it was created")
                .contains("Account").doesNotContain("Ledger");
        assertThat(after.changed()).singleElement().satisfies(changed -> {
            assertThat(changed.id()).isEqualTo(a1);
            assertThat(changed.type()).as("as it is named now").isEqualTo("Ledger");
        });
    }

    /** Draws IDs "g1", "g2" and so on and accepts any text, so that a test can supply IDs of any length. */
    private static final class TextIds implements IdGenerator {

        private int next = 1;

        @Override
        public String next() {
            return "g" + next++;
        }

        @Override
        public boolean accepts(String text) {
            return text != null && !text.isEmpty();
        }

        @Override
        public IdGenerator copy() {
            TextIds copy = new TextIds();
            copy.next = next;
            return copy;
        }
    }

    @Test
    void aChangeListsEntitiesAndRelationshipsByTheTextOfTheirIdsWhateverTheirLength() {
        InMemoryStore texts = new InMemoryStore(clock, new TextIds(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
        assertThat(texts.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);

        CommitResult result = texts.commit(Transaction.by(PRINCIPAL)
                .create(new EntityId("b"), "Person", from(1, 1), values("name", "B"))
                .create(new EntityId("aa"), "Person", from(1, 1), values("name", "AA"))
                .create(new EntityId("ab"), "Person", from(1, 1), values("name", "AB"))
                .create(new EntityId("Zed"), "Person", from(1, 1), values("name", "Zed"))
                .create(new EntityId("a"), "Person", from(1, 1), values("name", "A"))
                .build());

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(transaction(texts.changes(Basis.NONE, 10).get(1)).changed()).extracting(item -> item.id().value())
                .containsExactly("Zed", "a", "aa", "ab", "b");
    }

    // ------------------------------------------------------------------ reading without a lock

    @Test
    void readingChangesAndFollowingThemTakeNoLockOfTheWriter() throws Exception {
        registerReferenceModel();
        ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);

        List<Change> read = store.underWriterLock(() -> {
            // The writer lock is held here, as it is during a commit; reads and subscriptions go on in other threads.
            store.changeFeed(Basis.NONE).subscribe(subscriber);
            List<Change> delivered = subscriber.next(2);
            CompletableFuture<List<Change>> reading = CompletableFuture.supplyAsync(() -> store.changes(Basis.NONE, 10));
            try {
                assertThat(reading.get(RecordingSubscriber.TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(delivered);
                return reading.get();
            } catch (Exception e) {
                throw new AssertionError("Reading the changes waited for the writer.", e);
            }
        });

        assertThat(read).hasSize(2);
        subscriber.subscription().cancel();
    }

    @Test
    void readersThatRunWhileACommitterWritesSeeConsecutiveBasesAndWhatEachChangeListed() throws Exception {
        registerReferenceModel();
        int commits = 150;
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        CompletableFuture<Void> committer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < commits; i++) {
                commit(Transaction.by(PRINCIPAL).create("Person", from(1, 1), values("name", "P" + i)).build());
            }
            done.set(true);
        });
        List<CompletableFuture<Void>> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            readers.add(CompletableFuture.runAsync(() -> {
                Basis cursor = Basis.NONE;
                while (!done.get() || cursor.value() < commits + 1) {
                    List<Change> changes = store.changes(cursor, 7);
                    reads.incrementAndGet();
                    for (Change change : changes) {
                        assertThat(change.basis().value()).isEqualTo(cursor.value() + 1);
                        cursor = change.basis();
                        if (change instanceof TransactionChange transaction) {
                            assertThat(transaction.changed()).as("what the commit created is listed with it")
                                    .singleElement().satisfies(changed -> assertThat(changed.created()).isTrue());
                        }
                    }
                }
            }));
        }

        committer.get(60, TimeUnit.SECONDS);
        for (CompletableFuture<Void> reader : readers) {
            reader.get(60, TimeUnit.SECONDS);
        }
        assertThat(reads.get()).isPositive();
    }

    // ------------------------------------------------------------------ purges

    private List<String> changeKeysMentioning(String id) {
        List<String> found = new ArrayList<>();
        try (var all = keys.scan(KeyRange.prefix(new byte[] {LedgerCodec.Kind.CHANGE.prefix()}))) {
            all.forEach(entry -> {
                if (LedgerCodec.parseChangeKey(entry.key()).id().equals(id)) {
                    found.add(HexFormat.of().formatHex(entry.key()));
                }
            });
        }
        return found;
    }

    @Test
    void aPurgeDeletesTheEntriesOfWhatItRemovesAtEveryBasisThatChangedItAndLeavesTheOthers() {
        registerReferenceModel();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        Receipt employment = ReferenceStories.replay(store, clock, ReferenceStories.rd3Employment(beta, alice));
        RelationshipId employs = relationship(employment, BETA_EMPLOYS_ALICE);
        // Alice is changed three more times: renamed from March, corrected, and ended in the autumn.
        commit(Transaction.by(PRINCIPAL).patch(alice, from(3, 1), values("name", "Alicia")).build());
        commit(Transaction.by(PRINCIPAL).patch(alice, from(2, 1), values("name", "Ali")).build());
        commit(Transaction.by(PRINCIPAL).patch(employs, from(5, 1), values("title", "Lead")).build());
        commit(Transaction.by(PRINCIPAL).patch(acme, from(1, 1), values("name", "ACME Corp")).build());
        assertThat(changeKeysMentioning(alice.value())).as("created and changed twice").hasSize(3);
        assertThat(changeKeysMentioning(employs.value())).as("created and changed once").hasSize(2);

        PurgeResult result = store.purge(ReferenceStories.rd11PurgeAlice(alice));

        assertThat(result).isInstanceOf(PurgeResult.Purged.class);
        assertThat(changeKeysMentioning(alice.value())).isEmpty();
        assertThat(changeKeysMentioning(employs.value())).isEmpty();
        assertThat(changeKeysMentioning(acme.value())).as("what remains keeps its entries").hasSize(2);
        List<Change> changes = store.changes(Basis.NONE, 100);
        assertThat(changes).hasSize(8);
        assertThat(changes.stream().filter(TransactionChange.class::isInstance).map(TransactionChange.class::cast)
                .flatMap(change -> change.changed().stream()).map(Changed::id))
                .doesNotContain(alice, employs).contains(acme, beta);
        assertThat(transaction(changes.get(1)).changed()).extracting(Changed::id)
                .as("RD-1 lists the parties that remain").containsExactlyInAnyOrder(acme, beta);
        assertThat(transaction(changes.get(2)).changed()).as("RD-3 created only the purged relationship").isEmpty();
        assertThat(transaction(changes.get(3)).changed()).as("the renaming of Alice").isEmpty();
        assertThat(transaction(changes.get(4)).changed()).as("the correction of Alice").isEmpty();
        assertThat(transaction(changes.get(5)).changed()).as("the title of the purged relationship").isEmpty();
        assertThat(transaction(changes.get(6)).changed()).extracting(Changed::id).containsExactly(acme);
        assertThat(changes.stream().filter(TransactionChange.class::isInstance).map(TransactionChange.class::cast)
                .map(change -> change.transaction().basis()).toList()).as("their records stay")
                .containsExactly(new Basis(2), new Basis(3), new Basis(4), new Basis(5), new Basis(6), new Basis(7));
        assertThat(changes.getLast()).isInstanceOf(PurgeChange.class);
    }

    @Test
    void aPurgeThatTakesTheTargetOfACompositionDeletesItsEntriesToo() {
        registerReferenceModel();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        TemporaryId home = new TemporaryId("home");
        TemporaryId hasAddress = new TemporaryId("hasAddress");
        Receipt withAddress = commit(Transaction.by(PRINCIPAL)
                .create(home, "Address", from(1, 1), values("street", "1 Main St", "city", "Springfield",
                        "country", "US"))
                .relate(hasAddress, "HasAddress", alice, home, from(1, 1), values()).build());
        EntityId address = (EntityId) withAddress.temporaryIds().get(home);
        RelationshipId link = (RelationshipId) withAddress.temporaryIds().get(hasAddress);
        assertThat(transaction(store.changes(parties.basis(), 1).getFirst()).changed()).hasSize(2);

        store.purge(ReferenceStories.rd11PurgeAlice(alice));

        assertThat(transaction(store.changes(parties.basis(), 1).getFirst()).changed()).as("nothing remains")
                .isEmpty();
        assertThat(changeKeysMentioning(address.value())).isEmpty();
        assertThat(changeKeysMentioning(link.value())).isEmpty();
    }

    @Test
    void aPurgeIsAChangeOfItsOwnWhoseRecordNamesNoOne() {
        registerReferenceModel();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        store.purge(ReferenceStories.rd11PurgeAlice(entity(parties, ALICE)));

        PurgeChange purge = (PurgeChange) store.changes(parties.basis(), 1).getFirst();

        assertThat(purge.purge().entities()).isEqualTo(1);
        assertThat(purge.purge().relationships()).isZero();
        assertThat(purge.purge().principal()).isEqualTo(ReferenceStories.PURGE_PRINCIPAL);
        assertThat(purge.toString()).doesNotContain(entity(parties, ALICE).value());
    }

    // ------------------------------------------------------------------ other kinds of stores

    @Test
    void anInMemoryStoreFromTheFactoryReadsAndFollowsItsChangesLikeAnyOther() {
        Store created = Stores.inMemory(StoreSettings.builder().clock(clock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules()).build());
        assertThat(created.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        RecordingSubscriber subscriber = new RecordingSubscriber(1);
        created.changeFeed(Basis.NONE).subscribe(subscriber);

        assertThat(subscriber.next()).isEqualTo(created.changes(Basis.NONE, 1).getFirst());
        subscriber.subscription().cancel();
    }
}
