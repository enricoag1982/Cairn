package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.EFFECTIVE_TIMES;
import static io.github.enricoag1982.cairn.core.data.ReferenceTales.entity;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1_MOVED;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.core.kv.Batch;
import io.github.enricoag1982.cairn.core.kv.FakePersistentKeyValueStore;
import io.github.enricoag1982.cairn.core.kv.KeyRange;
import io.github.enricoag1982.cairn.core.kv.KeyValueStore;
import io.github.enricoag1982.cairn.core.kv.KeyValueStores;
import io.github.enricoag1982.cairn.core.storage.StoredMetadata;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store over a persistent key-value store (ADR-0028, design D2 to D4 of {@code add-rocksdb-store}), on a fake that
 * keeps its keys in memory: what a commit, a registration and a purge write, what opening reads back, and what happens
 * when a write fails. The scenarios of RocksDB stores are in {@code cairn-rocksdb}; these tests need no database.
 */
class PersistentStoreTest {

    private final TestClock clock = new TestClock();
    private FakePersistentKeyValueStore keys = new FakePersistentKeyValueStore();

    private DurableStore open() {
        return open(clock, IdGenerators.deterministic(), ReferenceModel.executableRules());
    }

    private DurableStore open(TestClock clock, IdGenerator generator, List<Rule> rules) {
        return KeyValueStores.openDurable(keys, StoreSettings.builder().clock(clock).idGenerator(generator)
                .rules(rules).declaredRules(ReferenceModel.declaredRules()).build());
    }

    /** Closes the store and opens the same keys again, as an application that restarts does. */
    private DurableStore reopen(DurableStore store) {
        store.close();
        keys = keys.reopened();
        return open();
    }

    private DurableStore openWithReferenceModel() {
        DurableStore store = open();
        DurableFixture.registerReferenceModel(store);
        return store;
    }

    private Receipt replay(DurableStore store, ReferenceStories.Recording recording) {
        return ReferenceStories.replay(store, clock, recording);
    }

    /** The first bytes of the keys of a batch's operations, which name their kinds. */
    private static Set<Integer> kinds(Batch batch, Predicate<Batch.Operation> which) {
        Set<Integer> kinds = new TreeSet<>();
        batch.operations().stream().filter(which).forEach(operation -> kinds.add(operation.key()[0] & 0xFF));
        return kinds;
    }

    private static final Predicate<Batch.Operation> PUTS = operation -> operation instanceof Batch.Put;
    private static final Predicate<Batch.Operation> DELETES = operation -> operation instanceof Batch.Delete;

    // The kinds of keys: the ledger's (LedgerCodec.Kind) are 1 to 6, the metadata's start at 16.
    private static final int CURRENT = 1;
    private static final int TYPE = 4;
    private static final int FORMAT = 16;
    private static final int LATEST = 17;
    private static final int SCHEMA = 18;
    private static final int TRANSACTION = 19;
    private static final int KEYED = 20;
    private static final int PURGE = 21;
    private static final int PURGED_ID = 22;
    private static final int PURGED_KEY = 23;
    private static final int COMPACTION_PENDING = 24;

    private static ReferenceStories.Recording rd1WithAKey() {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        return new ReferenceStories.Recording(rd1.recordedAt(), new Transaction(rd1.transaction().principal(),
                Optional.empty(), Optional.empty(), Optional.of("import-rd1"), rd1.transaction().writes()));
    }

    // ------------------------------------------------------------------ opening a new store

    @Test
    void aNewStoreIsEmptyAndGetsItsFormatVersionBeforeAnythingElse() {
        DurableStore store = open();

        assertThat(store.latestBasis()).isEqualTo(Basis.NONE);
        assertThat(store.transactions()).isEmpty();
        assertThat(store.schema().history()).isEmpty();
        assertThat(StoredMetadata.formatVersion(keys.data())).hasValue(StoredMetadata.FORMAT_VERSION);
        assertThat(keys.batches()).singleElement().satisfies(batch -> assertThat(kinds(batch, PUTS))
                .containsExactly(FORMAT));
    }

    // ------------------------------------------------------------------ what is written

    @Test
    void aSchemaRegistrationWritesItsRecordAndTheLatestBasisInOneBatch() {
        DurableStore store = open();
        int before = keys.batches().size();

        DurableFixture.registerReferenceModel(store);

        assertThat(keys.batches()).hasSize(before + 1);
        assertThat(kinds(keys.batches().getLast(), PUTS)).containsExactly(LATEST, SCHEMA);
        assertThat(kinds(keys.batches().getLast(), DELETES)).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(new Basis(1));
        assertThat(StoredMetadata.latest(keys.data())).hasValueSatisfying(latest -> {
            assertThat(latest.basis()).isEqualTo(new Basis(1));
            assertThat(latest.transactionTime()).isEqualTo(store.schema().history().getFirst().transactionTime());
        });
    }

    @Test
    void aCommitWritesItsStateAndItsMetadataInOneBatch() {
        DurableStore store = openWithReferenceModel();
        int before = keys.batches().size();

        replay(store, rd1WithAKey());

        assertThat(keys.batches()).as("one batch for one commit").hasSize(before + 1);
        Batch batch = keys.batches().getLast();
        assertThat(kinds(batch, PUTS)).as("the ledger's keys and the metadata's").contains(CURRENT, TYPE)
                .contains(LATEST, TRANSACTION, KEYED).doesNotContain(FORMAT, SCHEMA, PURGE);
        assertThat(kinds(batch, DELETES)).isEmpty();
        assertThat(keys.events().getLast()).isEqualTo("apply");
    }

    @Test
    void aTransactionWithoutAnIdempotencyKeyWritesNoKeyedEntry() {
        DurableStore store = openWithReferenceModel();

        replay(store, ReferenceStories.rd1Parties());

        assertThat(kinds(keys.batches().getLast(), PUTS)).contains(LATEST, TRANSACTION).doesNotContain(KEYED);
    }

    @Test
    void dryRunsAndRejectedTransactionsWriteNothing() {
        DurableStore store = openWithReferenceModel();
        replay(store, ReferenceStories.rd1Parties());
        int before = keys.batches().size();
        Transaction rejected = Transaction.by("importer").create("Person", from(1, 1), values()).build();

        assertThat(store.dryRun(Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob")).build()))
                .isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(store.commit(rejected)).isInstanceOf(CommitResult.Rejected.class);
        assertThat(store.purge(Purge.by("dpo").reason("none").entity(new EntityId("nobody")).build()))
                .isInstanceOf(PurgeResult.Rejected.class);

        assertThat(keys.batches()).hasSize(before);
        assertThat(keys.events()).doesNotContain("flush", "compact");
    }

    @Test
    void aPurgeIsOneBatchThatDeletesTheKeysAndWritesItsRecordAndThenFlushesAndCompacts() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, rd1WithAKey());
        replay(store, ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        int before = keys.batches().size();
        int eventsBefore = keys.events().size();

        PurgeResult result = store.purge(ReferenceStories.rd11PurgeAlice(entity(parties, ALICE)));

        assertThat(result).isInstanceOf(PurgeResult.Purged.class);
        assertThat(keys.batches()).as("one batch for the purge, and one that deletes its note").hasSize(before + 2);
        Batch batch = keys.batches().get(before);
        assertThat(kinds(batch, DELETES)).as("the keys of what it removes and the transaction of the key")
                .contains(CURRENT, TYPE, KEYED);
        assertThat(kinds(batch, PUTS)).as("its record, the digests of the IDs, the key that stays unusable, the note")
                .containsExactly(LATEST, PURGE, PURGED_ID, PURGED_KEY, COMPACTION_PENDING);
        assertThat(keys.events().subList(eventsBefore, keys.events().size()))
                .as("durable first, then flushed and compacted, and the note deleted before the purge returns")
                .containsExactly("apply", "flush", "compact", "apply");
        Batch noteDeleted = keys.batches().getLast();
        assertThat(kinds(noteDeleted, DELETES)).containsExactly(COMPACTION_PENDING);
        assertThat(kinds(noteDeleted, PUTS)).isEmpty();
        assertThat(kindsOfEntries(keys.data())).as("no note is left").doesNotContain(COMPACTION_PENDING);
        assertThat(store.latestBasis()).isEqualTo(new Basis(4));
    }

    @Test
    void writingASnapshotFlushesAndReturnsTheLatestBasis() {
        DurableStore store = openWithReferenceModel();
        replay(store, ReferenceStories.rd1Parties());
        int eventsBefore = keys.events().size();

        Basis snapshot = store.writeSnapshot();

        assertThat(snapshot).isEqualTo(store.latestBasis()).isEqualTo(new Basis(2));
        assertThat(keys.events().subList(eventsBefore, keys.events().size())).containsExactly("flush");
        assertThat(KeyValueStores.openDurable(new FakePersistentKeyValueStore(), StoreSettings.defaults())
                .writeSnapshot()).as("a store with nothing committed").isEqualTo(Basis.NONE);
    }

    // ------------------------------------------------------------------ opening what was written

    @Test
    void aReopenedStoreHoldsWhatWasCommittedAtTheSameBasesAndTransactionTimes() {
        DurableStore first = openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.everyStory(first, clock);
        Transaction keyed = Transaction.by(ReferenceStories.PRINCIPAL).idempotencyKey("bob")
                .reason("A new hire").onBehalfOf("hr").create("Person", from(1, 1), values("name", "Bob")).build();
        DurableFixture.commit(first, keyed);
        Origin extras = new Origin("extras", "1.0");
        SchemaDefinition extra = SchemaDefinition.builder().entityType("Extra",
                type -> type.attribute("a1", ValueType.STRING)).build();
        assertThat(first.schema().register(extras, extra)).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(first.purge(Purge.by("dpo").reason("erasure").entity(entity(told.parties, ALICE)).build()))
                .isInstanceOf(PurgeResult.Purged.class);
        StoreEquivalence.Observation before = StoreEquivalence.observe(first, told.ids(), EFFECTIVE_TIMES,
                List.of(keyed));

        DurableStore reopened = reopen(first);

        StoreEquivalence.assertEquivalent(before, StoreEquivalence.observe(reopened, told.ids(), EFFECTIVE_TIMES,
                List.of(keyed)));
        assertThat(reopened.schema().history()).hasSize(2);
        assertThat(reopened.purges()).singleElement().satisfies(record -> assertThat(record.reason())
                .isEqualTo("erasure"));
        assertThat(reopened.wasPurged(entity(told.parties, ALICE))).isTrue();
        assertThat(reopened.schema().storedDefinition("extras")).hasValueSatisfying(stored ->
                assertThat(stored.origin()).isEqualTo(extras));
    }

    @Test
    void theNextCommitAfterReopeningGetsTheNextBasisAndALaterTransactionTimeEvenIfTheClockWentBack() {
        DurableStore first = openWithReferenceModel();
        clock.moveTo(on(3, 1));
        Receipt last = DurableFixture.commit(first, Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());
        first.close();
        keys = keys.reopened();
        TestClock earlier = new TestClock();
        earlier.moveTo(on(1, 1));

        DurableStore reopened = open(earlier, IdGenerators.deterministic(), ReferenceModel.executableRules());
        Receipt receipt = DurableFixture.commit(reopened, Transaction.by("importer")
                .create(BETA, "Organization", from(1, 1), values("name", "Beta")).build());

        assertThat(receipt.basis()).isEqualTo(new Basis(last.basis().value() + 1));
        assertThat(receipt.transactionTime()).isAfter(last.transactionTime());
    }

    @Test
    void anIdempotencyKeyAfterReopeningGivesTheReceiptOfTheFirstCommit() {
        DurableStore first = openWithReferenceModel();
        Transaction transaction = Transaction.by("importer").idempotencyKey("import-42")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build();
        Receipt receipt = DurableFixture.commit(first, transaction);

        DurableStore reopened = reopen(first);

        assertThat(reopened.commit(transaction)).isEqualTo(new CommitResult.Committed(receipt));
        assertThat(reopened.latestBasis()).isEqualTo(receipt.basis());
        assertThat(keys.batches()).as("a retry writes nothing").isEmpty();
    }

    @Test
    void anIdempotencyKeyThatAPurgeRemovedStaysUnusableAfterReopeningAndNothingOfAliceIsLeftInTheKeys() {
        DurableStore first = openWithReferenceModel();
        ReferenceStories.Recording rd1 = rd1WithAKey();
        Receipt parties = replay(first, rd1);
        EntityId alice = entity(parties, ALICE);
        assertThat(everyEntryContains(keys.data(), "Alice")).as("the keys hold Alice before the purge").isTrue();
        assertThat(first.purge(ReferenceStories.rd11PurgeAlice(alice))).isInstanceOf(PurgeResult.Purged.class);

        DurableStore reopened = reopen(first);
        CommitResult again = reopened.commit(rd1.transaction());

        assertThat(again).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) again).problems()).singleElement().extracting(Problem::code)
                .isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
        assertThat(reopened.wasPurged(alice)).isTrue();
        assertThat(everyEntryContains(keys.data(), "Alice")).as("no key or value holds Alice").isFalse();
        assertThat(everyEntryContains(keys.data(), alice.value())).as("nor her ID").isFalse();
        assertThat(kindsOfEntries(keys.data())).as("the transaction of the key is gone, the key is kept")
                .doesNotContain(KEYED).contains(PURGED_KEY, PURGED_ID, PURGE);
    }

    private static Set<Integer> kindsOfEntries(KeyValueStore store) {
        Set<Integer> kinds = new TreeSet<>();
        try (Stream<KeyValueStore.Entry> all = store.scan(KeyRange.all())) {
            all.forEach(entry -> kinds.add(entry.key()[0] & 0xFF));
        }
        return kinds;
    }

    private static boolean everyEntryContains(KeyValueStore store, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        try (Stream<KeyValueStore.Entry> all = store.scan(KeyRange.all())) {
            return all.anyMatch(entry -> contains(entry.key(), needle) || contains(entry.value(), needle));
        }
    }

    private static boolean contains(byte[] bytes, byte[] needle) {
        for (int start = 0; start + needle.length <= bytes.length; start++) {
            if (Arrays.equals(bytes, start, start + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anotherIdGeneratorIsRefusedNamingAnIdOfTheStoreAndTheKeysAreClosed() {
        DurableStore first = openWithReferenceModel();
        List<String> held = ReferenceTales.rd1ToRd5(first, clock).ids().stream().map(id -> id.value()).toList();
        first.close();
        keys = keys.reopened();

        assertThatThrownBy(() -> open(clock, new Rejecting(), ReferenceModel.executableRules()))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(held).anyMatch(id -> e.getMessage().contains(id)));

        assertThat(keys.isClosed()).as("a store that fails to open closes its keys").isTrue();
        keys = keys.reopened();
        assertThat(open().latestBasis()).as("nothing was changed").isEqualTo(new Basis(9));
    }

    /** An ID generator that accepts no ID and is never asked for one. */
    private record Rejecting() implements IdGenerator {

        @Override
        public String next() {
            throw new AssertionError("Opening asked the ID generator.");
        }

        @Override
        public boolean accepts(String text) {
            return false;
        }

        @Override
        public IdGenerator copy() {
            return this;
        }
    }

    @Test
    void aNewEntityAfterReopeningGetsAnIdThatTheStoreDoesNotHave() {
        DurableStore first = openWithReferenceModel();
        Receipt parties = replay(first, ReferenceStories.rd1Parties());

        DurableStore reopened = reopen(first);
        Receipt receipt = DurableFixture.commit(reopened, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build());

        assertThat(parties.created()).hasSize(3);
        assertThat(receipt.created()).singleElement().isNotIn(parties.created());
    }

    @Test
    void rulesAreRunAgainAfterOpeningToLearnWhatTheyRead() {
        List<Rule> rules = DurableFixture.rulesWithEmployerHoldsAccount();
        DurableStore first = open(clock, IdGenerators.deterministic(), rules);
        assertThat(first.schema().register(ReferenceModel.origin("1.0.0"), Definitions.withRule(
                ReferenceModel.definition(), "Employs", EmployerHoldsAccountRule.DESCRIPTOR)))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = replay(first, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt accounts = replay(first, ReferenceStories.rd5Accounts(beta, acme));
        replay(first, ReferenceStories.rd3Employment(beta, entity(parties, ALICE)));
        Transaction movesA1 = Transaction.by("importer")
                .endDate(ReferenceTales.relationship(accounts, HELD_BY_A1), on(6, 1))
                .relate(HELD_BY_A1_MOVED, "HeldBy", entity(accounts, A1), acme, from(6, 1), values()).build();
        clock.moveTo(on(5, 20));
        List<Problem> before = ((DryRunResult.Rejected) first.dryRun(movesA1)).problems();
        first.close();
        keys = keys.reopened();

        DurableStore reopened = open(clock, IdGenerators.deterministic(), rules);
        CommitResult result = reopened.commit(movesA1);

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) result).problems()).as("as before the store was closed")
                .isEqualTo(before).singleElement().satisfies(problem -> assertThat(problem.ruleCode())
                        .contains("employer-holds-account/no-account"));
    }

    @Test
    void aFormatVersionThatIsNotKnownIsRefusedNamingItAndTheKeysAreClosed() {
        DurableStore first = openWithReferenceModel();
        first.close();
        keys = keys.reopened();
        Batch other = new Batch();
        StoredMetadata.putFormatVersion(other, 2);
        keys.data().apply(other);

        assertThatThrownBy(() -> open()).isInstanceOf(IllegalStateException.class).hasMessageContaining("version 2");

        assertThat(keys.isClosed()).isTrue();
    }

    @Test
    void keysWithoutAFormatVersionAreNotAStoreThatCairnWrote() {
        keys.data().apply(new Batch().put(new byte[] {1, 2, 3}, new byte[] {4}));

        assertThatThrownBy(() -> open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no format version");

        assertThat(keys.isClosed()).isTrue();
    }

    @Test
    void aDamagedRecordFailsOpeningAndNamesItsKey() {
        DurableStore first = openWithReferenceModel();
        first.close();
        keys = keys.reopened();
        byte[] key = keys.data().scan(KeyRange.prefix(new byte[] {SCHEMA})).findFirst().orElseThrow().key();
        keys.data().apply(new Batch().put(key, new byte[] {1, 2, 3}));

        assertThatThrownBy(() -> open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema record").hasMessageContaining("is damaged");

        assertThat(keys.isClosed()).isTrue();
    }

    // ------------------------------------------------------------------ a write that fails

    @Test
    void aCommitThatCannotBeWrittenIsNotAppliedAndTheStoreRefusesWritesUntilItIsOpenedAgain() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, ReferenceStories.rd1Parties());
        Basis before = store.latestBasis();
        keys.failApplies();
        Transaction transaction = Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob"))
                .build();

        assertThatThrownBy(() -> store.commit(transaction)).isInstanceOf(UncheckedIOException.class);

        assertThat(store.latestBasis()).as("the transaction is not recorded").isEqualTo(before);
        assertThat(store.transactions()).hasSize(1);
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Person")).as("nor visible")
                .extracting(state -> state.values().get("name")).doesNotContain(
                        Value.of("Bob"));
        assertThatThrownBy(() -> store.commit(transaction)).as("a further commit").isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> store.schema().register(new Origin("extras", "1.0"), SchemaDefinition.builder()
                .entityType("Extra", type -> type.attribute("a1", ValueType.STRING)).build()))
                .as("a registration").isInstanceOf(IllegalStateException.class);
        assertThat(store.dryRun(transaction)).as("dry runs go on").isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Party")).as("views read the earlier state")
                .hasSize(parties.created().size());

        store.close();
        keys = keys.reopened();
        DurableStore reopened = open();
        assertThat(reopened.latestBasis()).as("the earlier state is on").isEqualTo(before);
        assertThat(DurableFixture.commit(reopened, transaction).basis()).isEqualTo(new Basis(before.value() + 1));
    }

    @Test
    void aSchemaRegistrationThatCannotBeWrittenIsNotAppliedAndTheStoreRefusesWrites() {
        DurableStore store = openWithReferenceModel();
        Basis before = store.latestBasis();
        keys.failApplies();

        assertThatThrownBy(() -> store.schema().register(new Origin("extras", "1.0"), SchemaDefinition.builder()
                .entityType("Extra", type -> type.attribute("a1", ValueType.STRING)).build()))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(store.latestBasis()).isEqualTo(before);
        assertThat(store.schema().history()).hasSize(1);
        assertThat(store.schema().current().entityType("Extra")).isEmpty();
        assertThatThrownBy(() -> store.commit(Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPurgeThatCannotBeWrittenRemovesNothing() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, rd1WithAKey());
        EntityId alice = entity(parties, ALICE);
        keys.failApplies();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entity(alice)).as("Alice is still there").isPresent();
        assertThat(store.purges()).isEmpty();
        assertThat(store.wasPurged(alice)).isFalse();
        assertThat(keys.events()).doesNotContain("flush", "compact");
        assertThatThrownBy(() -> store.commit(Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build()))
                .as("the store refuses commits").isInstanceOf(IllegalStateException.class);

        store.close();
        keys = keys.reopened();
        DurableStore reopened = open();
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entity(alice)).as("after reopening").isPresent();
        assertThat(reopened.commit(rd1WithAKey().transaction())).as("and so is the key, which gives its receipt")
                .isEqualTo(new CommitResult.Committed(parties));
    }

    @Test
    void aPurgeThatCannotCompactIsAppliedAndTheStoreRefusesCommitsBecauseTheFilesMayHoldTheData() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        keys.failCompactions();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entity(alice)).as("the purge was applied").isEmpty();
        assertThat(store.wasPurged(alice)).isTrue();
        assertThatThrownBy(() -> store.commit(Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(kindsOfEntries(keys.data())).as("the note stays").contains(COMPACTION_PENDING);

        assertThat(reopenAndFinish(store)).as("opening finishes the compaction, then deletes the note")
                .containsExactly("flush", "compact", "apply");
    }

    @Test
    void aPurgeThatCannotFlushIsAppliedAndTheNoteStaysAndOpeningFinishesIt() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        keys.failFlushes();

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(keys.events()).as("it did not go on to compact").doesNotContain("compact");
        assertThat(kindsOfEntries(keys.data())).contains(COMPACTION_PENDING);
        assertThat(reopenAndFinish(store)).containsExactly("flush", "compact", "apply");
    }

    @Test
    void aPurgeThatCannotDeleteItsNoteIsAppliedAndTheStoreRefusesCommitsUntilOpeningFinishesIt() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, ReferenceStories.rd1Parties());
        EntityId alice = entity(parties, ALICE);
        keys.failAppliesAfter(1);

        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(alice)))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(keys.events()).containsSubsequence("apply", "flush", "compact");
        assertThat(store.wasPurged(alice)).isTrue();
        assertThatThrownBy(() -> store.commit(Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(kindsOfEntries(keys.data())).as("the note stays").contains(COMPACTION_PENDING);
        assertThat(reopenAndFinish(store)).containsExactly("flush", "compact", "apply");
    }

    /**
     * Closes the store and opens the keys again, and returns what opening did to the key-value store: the store is
     * the purged one, and no note is left.
     */
    private List<String> reopenAndFinish(DurableStore store) {
        store.close();
        keys = keys.reopened();
        DurableStore reopened = open();
        assertThat(reopened.purges()).hasSize(1);
        assertThat(kindsOfEntries(keys.data())).doesNotContain(COMPACTION_PENDING);
        assertThat(DurableFixture.commit(reopened, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build())).as("it commits again").isNotNull();
        return keys.events().subList(0, keys.events().indexOf("apply") + 1);
    }

    @Test
    void openingFinishesACompactionThatWasPendingOnceAndNotAgain() {
        DurableStore first = openWithReferenceModel();
        replay(first, ReferenceStories.rd1Parties());
        first.close();
        keys = keys.reopened();
        Batch pending = new Batch();
        StoredMetadata.putCompactionPending(pending);
        keys.data().apply(pending);

        DurableStore opened = open();

        assertThat(keys.events()).as("it flushed, compacted and deleted the note, before anything else was written")
                .containsExactly("flush", "compact", "apply");
        assertThat(opened.transactions()).hasSize(1);
        assertThat(kindsOfEntries(keys.data())).doesNotContain(COMPACTION_PENDING);
        opened.close();
        keys = keys.reopened();
        open();
        assertThat(keys.events()).as("a store without a note compacts nothing").isEmpty();
    }

    @Test
    void aStoreWhoseCompactionCannotBeFinishedWhenItIsOpenedFailsToOpenAndKeepsTheNote() {
        DurableStore first = openWithReferenceModel();
        first.close();
        keys = keys.reopened();
        Batch pending = new Batch();
        StoredMetadata.putCompactionPending(pending);
        keys.data().apply(pending);
        keys.failCompactions();

        assertThatThrownBy(() -> open()).isInstanceOf(UncheckedIOException.class);

        assertThat(keys.isClosed()).as("a store that fails to open closes its keys").isTrue();
        assertThat(kindsOfEntries(keys.data())).as("the note stays").contains(COMPACTION_PENDING);
    }

    // ------------------------------------------------------------------ closing

    @Test
    void aClosedStoreRefusesEveryCallButCloseAndClosingClosesTheKeys() {
        DurableStore store = openWithReferenceModel();
        var registry = store.schema();
        store.close();

        assertThat(keys.isClosed()).isTrue();
        assertThatThrownBy(() -> store.commit(Transaction.by("importer").build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.dryRun(Transaction.by("importer").build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.view(Coordinates.effectiveAt(on(1, 1)))).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(store::latestBasis).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::transactions).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::purges).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::declaredRules).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::writeSnapshot).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.purge(Purge.by("dpo").reason("none").entity(new EntityId("x")).build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::schema).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::current).as("the registry that was taken before").isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(ReferenceModel.origin("1.0.1"), ReferenceModel.definition()))
                .isInstanceOf(IllegalStateException.class);
        store.close();
        assertThat(keys.events().stream().filter("close"::equals)).as("closing again does nothing").hasSize(1);
    }

    @Test
    void closingFlushesTheKeysBeforeItClosesThemAsClosingADirectoryStoreWritesASnapshot() {
        DurableStore store = openWithReferenceModel();
        replay(store, ReferenceStories.rd1Parties());
        int eventsBefore = keys.events().size();

        store.close();

        assertThat(keys.events().subList(eventsBefore, keys.events().size())).containsExactly("flush", "close");
    }

    @Test
    void aStoreThatRefusesWritesAfterAFailedWriteDoesNotFlushWhenItIsClosedButClosesTheKeys() {
        DurableStore store = openWithReferenceModel();
        keys.failApplies();
        assertThatThrownBy(() -> store.commit(Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build())).isInstanceOf(UncheckedIOException.class);
        int eventsBefore = keys.events().size();

        store.close();

        assertThat(keys.events().subList(eventsBefore, keys.events().size())).containsExactly("close");
        assertThat(keys.isClosed()).isTrue();
    }

    @Test
    void aStoreWhosePurgeCouldNotCompactDoesNotFlushAgainWhenItIsClosed() {
        DurableStore store = openWithReferenceModel();
        Receipt parties = replay(store, ReferenceStories.rd1Parties());
        keys.failCompactions();
        assertThatThrownBy(() -> store.purge(ReferenceStories.rd11PurgeAlice(entity(parties, ALICE))))
                .isInstanceOf(UncheckedIOException.class);
        int flushes = (int) keys.events().stream().filter("flush"::equals).count();

        store.close();

        assertThat(keys.events().stream().filter("flush"::equals)).as("no flush when closing").hasSize(flushes);
        assertThat(keys.events().getLast()).isEqualTo("close");
    }

    @Test
    void aFlushThatFailsWhenClosingStillClosesTheKeysAndTheCallThrows() {
        DurableStore store = openWithReferenceModel();
        keys.failFlushes();

        assertThatThrownBy(store::close).isInstanceOf(UncheckedIOException.class);

        assertThat(keys.isClosed()).as("the keys are closed, and the directory released").isTrue();
        assertThatThrownBy(store::latestBasis).isInstanceOf(IllegalStateException.class);
        store.close();
        assertThat(keys.events().stream().filter("close"::equals)).hasSize(1);
    }

    @Test
    void theLockOfADirectoryIsTheSameAsTheOneOfDirectoryStores(@TempDir Path directory) {
        Runnable release = KeyValueStores.lockDirectory(directory);

        assertThatThrownBy(() -> KeyValueStores.lockDirectory(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.toString());
        assertThatThrownBy(() -> Stores.durable(directory))
                .as("a store kept through a commit log").isInstanceOf(IllegalStateException.class);
        release.run();
        release.run();

        Stores.durable(directory).close();
        KeyValueStores.lockDirectory(directory).run();
    }
}
