package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.CUSTOMER_ROLE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PLAYED_BY;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.Changed;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
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
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenarios of the {@code change-feed} spec, one test each (ADR-0029, design of {@code add-change-feed}), on the
 * stories of the reference domain. The stores are in-memory stores, or RocksDB stores in the second run of the scenario
 * tests (see {@link TestBackend}); the scenarios of closing and reopening open the durable store of that run on a
 * directory: a store kept through a commit log and snapshot files, or a RocksDB store.
 */
class ChangeFeedScenariosTest {

    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");

    @TempDir
    Path directory;

    private final ReferenceStore reference = new ReferenceStore();
    private final List<DurableStore> opened = new ArrayList<>();

    @AfterEach
    void closeTheDurableStores() {
        opened.forEach(DurableStore::close);
    }

    // ------------------------------------------------------------------ helpers

    private static TransactionChange transaction(Change change) {
        assertThat(change).isInstanceOf(TransactionChange.class);
        return (TransactionChange) change;
    }

    /** The change of the data transaction that has a basis. */
    private TransactionChange changeAt(Store store, Basis basis) {
        List<Change> changes = store.changes(new Basis(basis.value() - 1), 1);
        assertThat(changes).singleElement().extracting(Change::basis).isEqualTo(basis);
        return transaction(changes.getFirst());
    }

    private static Changed changed(Id id, String type, boolean created, EffectivePeriod... periods) {
        return new Changed(id, type, created, List.of(periods));
    }

    /** RD-1, RD-5 (the accounts), RD-2, RD-3, RD-4, RD-5 (the balance and its correction) and the correction of RD-2. */
    private static List<Receipt> rd1ToRd5(Store store, TestClock clock) {
        List<Receipt> receipts = new ArrayList<>();
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        receipts.add(parties);
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt accounts = ReferenceStories.replay(store, clock, ReferenceStories.rd5Accounts(beta, acme));
        receipts.add(accounts);
        Receipt ownership = ReferenceStories.replay(store, clock, ReferenceStories.rd2Ownership(acme, beta));
        receipts.add(ownership);
        receipts.add(ReferenceStories.replay(store, clock,
                ReferenceStories.rd3Employment(beta, entity(parties, ALICE))));
        receipts.add(ReferenceStories.replay(store, clock, ReferenceStories.rd4CustomerRole(beta)));
        EntityId a1 = entity(accounts, A1);
        receipts.add(ReferenceStories.replay(store, clock, ReferenceStories.rd5BalancePatch(a1)));
        receipts.add(ReferenceStories.replay(store, clock, ReferenceStories.rd5BalanceCorrection(a1)));
        receipts.add(ReferenceStories.replay(store, clock,
                ReferenceStories.rd2ShareCorrection(relationship(ownership, ACME_OWNS_BETA))));
        return receipts;
    }

    /** Opens the durable store of the run on the directory, with the rules of the reference domain. */
    private DurableStore openDurable(TestClock clock) {
        StoreSettings settings = ReferenceStore.referenceSettings().clock(clock)
                .idGenerator(IdGenerators.deterministic()).build();
        DurableStore store = TestBackend.isRocksDb() ? TestBackend.openRocksDb(directory, settings)
                : Stores.durable(directory, settings);
        opened.add(store);
        return store;
    }

    // ------------------------------------------------------------------ one change per basis

    @Test
    @DisplayName("Scenario: Changes in basis order")
    void changesInBasisOrder() {
        Receipt parties = reference.createParties();
        Receipt employment = reference.replay(
                ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        reference.rejectReplay(ReferenceStories.rd12GammaWrongCheckDigits());
        assertThat(reference.store.dryRun(Transaction.by(PRINCIPAL)
                .create("Person", from(1, 1), values("name", "Dry")).build()))
                .isInstanceOf(DryRunResult.WouldCommit.class);

        List<Change> changes = reference.store.changes(Basis.NONE, 100);

        assertThat(changes).hasSize(3);
        assertThat(changes).extracting(Change::basis).containsExactly(new Basis(1), parties.basis(),
                employment.basis());
        assertThat(changes.get(0)).isEqualTo(new SchemaChange(reference.store.schema().history().getFirst()));
        assertThat(transaction(changes.get(1)).transaction()).isEqualTo(reference.store.transactions().get(0));
        assertThat(transaction(changes.get(2)).transaction()).isEqualTo(reference.store.transactions().get(1));
        assertThat(transaction(changes.get(2)).transaction().principal()).isEqualTo(PRINCIPAL);
        assertThat(changes.get(2).transactionTime()).isEqualTo(employment.transactionTime());
    }

    @Test
    @DisplayName("Scenario: Reading from a basis with a limit")
    void readingFromABasisWithALimit() {
        List<Receipt> receipts = rd1ToRd5(reference.store, reference.clock);
        Basis rd1 = receipts.getFirst().basis();
        Basis latest = reference.store.latestBasis();
        assertThat(latest.value()).isEqualTo(9);

        List<Change> first = reference.store.changes(rd1, 2);

        assertThat(first).extracting(Change::basis).containsExactly(new Basis(3), new Basis(4));
        List<Change> everything = new ArrayList<>(first);
        Basis cursor = first.getLast().basis();
        while (true) {
            List<Change> more = reference.store.changes(cursor, 2);
            if (more.isEmpty()) {
                break;
            }
            assertThat(more.getFirst().basis()).as("it continues at the next basis")
                    .isEqualTo(new Basis(cursor.value() + 1));
            assertThat(more).hasSizeLessThanOrEqualTo(2);
            everything.addAll(more);
            cursor = more.getLast().basis();
        }
        assertThat(cursor).as("until the latest").isEqualTo(latest);
        assertThat(everything).extracting(Change::basis).containsExactly(new Basis(3), new Basis(4), new Basis(5),
                new Basis(6), new Basis(7), new Basis(8), new Basis(9));
        assertThat(everything).isEqualTo(reference.store.changes(rd1, 100));
    }

    @Test
    void readingAfterTheLatestBasisGivesNothingAndALimitBelowOneIsRefused() {
        reference.createParties();
        Basis latest = reference.store.latestBasis();

        assertThat(reference.store.changes(latest, 10)).isEmpty();
        assertThat(reference.store.changes(new Basis(latest.value() + 5), 10)).isEmpty();
        assertThat(reference.store.changes(new Basis(Long.MAX_VALUE), 10)).isEmpty();
        assertThat(reference.store.changes(new Basis(-3), 10)).as("before the first").hasSize(2);
        assertThatThrownBy(() -> reference.store.changes(Basis.NONE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reference.store.changes(Basis.NONE, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reference.store.changes(Basis.NONE, Integer.MAX_VALUE)).hasSize(2);
        assertThat(reference.store.changes(Basis.NONE, 1)).singleElement().isInstanceOf(SchemaChange.class);
    }

    // ------------------------------------------------------------------ what a transaction changed

    @Test
    @DisplayName("Scenario: Entities created")
    void entitiesCreated() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);

        TransactionChange change = changeAt(reference.store, parties.basis());

        assertThat(change.changed()).containsExactlyInAnyOrder(
                changed(acme, "Organization", true, EffectivePeriod.from(on(1, 1))),
                changed(beta, "Organization", true, EffectivePeriod.from(on(1, 1))),
                changed(alice, "Person", true, EffectivePeriod.from(on(1, 1))));
        assertThat(change.changed()).extracting(item -> item.id().value()).as("ordered by ID as text")
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(change.transaction()).isEqualTo(reference.store.transactions().getFirst());
    }

    @Test
    @DisplayName("Scenario: A correction changes a later period only")
    void aCorrectionChangesALaterPeriodOnly() {
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        reference.replay(ReferenceStories.rd5BalancePatch(a1));
        Receipt correction = reference.replay(ReferenceStories.rd5BalanceCorrection(a1));
        assertThat(correction.transactionTime()).isAfterOrEqualTo(on(4, 20));

        TransactionChange change = changeAt(reference.store, correction.basis());

        assertThat(change.changed()).containsExactly(changed(a1, "Account", false, EffectivePeriod.from(on(3, 15))));
    }

    @Test
    @DisplayName("Scenario: A closed period")
    void aClosedPeriod() {
        Receipt parties = reference.createParties();
        Receipt customer = reference.replay(ReferenceStories.rd4CustomerRole(entity(parties, BETA)));
        EntityId role = entity(customer, CUSTOMER_ROLE);
        RelationshipId playedBy = relationship(customer, PLAYED_BY);
        EffectivePeriod period = EffectivePeriod.between(on(3, 1), on(10, 1));

        TransactionChange change = changeAt(reference.store, customer.basis());

        assertThat(change.changed()).containsExactlyInAnyOrder(changed(role, "CustomerRole", true, period),
                changed(playedBy, "PlayedBy", true, period));
        assertThat(change.changed()).extracting(item -> item.id().value()).isSortedAccordingTo(
                Comparator.naturalOrder());
    }

    @Test
    @DisplayName("Scenario: A transaction that changes nothing")
    void aTransactionThatChangesNothing() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        Receipt again = reference.commit(Transaction.by(PRINCIPAL)
                .replace(acme, from(1, 1), values("name", "ACME")).build());

        TransactionChange change = changeAt(reference.store, again.basis());

        assertThat(change.transaction()).as("its transaction record").isEqualTo(reference.store.transactions().getLast());
        assertThat(change.transaction().basis()).isEqualTo(again.basis());
        assertThat(change.changed()).as("it lists nothing").isEmpty();
        assertThat(reference.view(on(2, 1)).timeline(acme)).as("ACME's timeline is as it was").hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Ended by a cascade")
    void endedByACascade() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        Receipt withAddress = reference.commit(Transaction.by(PRINCIPAL)
                .create(HOME, "Address", from(1, 1), values("street", "1 Main St", "city", "Springfield",
                        "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", beta, HOME, from(1, 1), values()).build());
        EntityId home = entity(withAddress, HOME);
        RelationshipId hasAddress = relationship(withAddress, HAS_ADDRESS);

        Receipt ending = reference.commit(Transaction.by(PRINCIPAL).endDate(beta, on(6, 1)).build());

        assertThat(ending.ended()).containsExactlyInAnyOrder(hasAddress, home);
        TransactionChange change = changeAt(reference.store, ending.basis());
        assertThat(change.changed()).containsExactlyInAnyOrder(
                changed(beta, "Organization", false, EffectivePeriod.from(on(6, 1))),
                changed(hasAddress, "HasAddress", false, EffectivePeriod.from(on(6, 1))),
                changed(home, "Address", false, EffectivePeriod.from(on(6, 1))));
    }

    // ------------------------------------------------------------------ subscribing to changes

    @Test
    @DisplayName("Scenario: A subscription from a basis")
    void aSubscriptionFromABasis() {
        Receipt parties = reference.createParties();
        RecordingSubscriber subscriber = new RecordingSubscriber(10);
        reference.store.changeFeed(parties.basis()).subscribe(subscriber);

        Receipt employment = reference.replay(
                ReferenceStories.rd3Employment(entity(parties, BETA), entity(parties, ALICE)));
        Receipt customer = reference.replay(ReferenceStories.rd4CustomerRole(entity(parties, BETA)));

        List<Change> received = subscriber.next(2);
        assertThat(received).extracting(Change::basis).containsExactly(employment.basis(), customer.basis());
        assertThat(received).isEqualTo(reference.store.changes(parties.basis(), 10));
        assertThat(subscriber.signalThreads()).as("on a thread of the subscription, not the committing one")
                .hasSize(2).doesNotContain(Thread.currentThread());
        assertThat(subscriber.pending()).as("once each").isZero();
        subscriber.subscription().cancel();
    }

    @Test
    @DisplayName("Scenario: A consumer that requests one at a time")
    void aConsumerThatRequestsOneAtATime() {
        RecordingSubscriber subscriber = new RecordingSubscriber(1);
        reference.store.changeFeed(Basis.NONE).subscribe(subscriber);

        // The commits return whatever the consumer does.
        Receipt parties = reference.createParties();
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(entity(parties, ACME),
                entity(parties, BETA)));
        Receipt employment = reference.replay(ReferenceStories.rd3Employment(entity(parties, BETA),
                entity(parties, ALICE)));

        Change first = subscriber.next();
        assertThat(first).isInstanceOf(SchemaChange.class);
        assertThat(first.basis()).isEqualTo(new Basis(1));
        assertThat(subscriber.pending()).as("one change was requested, so one was delivered").isZero();
        assertThat(subscriber.calls()).isEqualTo(1);
        subscriber.subscription().request(1);
        assertThat(subscriber.next().basis()).as("the next one after another request").isEqualTo(parties.basis());
        assertThat(subscriber.pending()).isZero();
        assertThat(subscriber.calls()).isEqualTo(2);
        subscriber.subscription().request(2);
        assertThat(subscriber.next(2)).extracting(Change::basis).containsExactly(ownership.basis(),
                employment.basis());
        subscriber.subscription().cancel();
    }

    @Test
    @DisplayName("Scenario: A subscriber that throws")
    void aSubscriberThatThrows() {
        reference.createParties();
        RecordingSubscriber thrower = new RecordingSubscriber(10, change -> {
            throw new IllegalStateException("The subscriber fails.");
        });
        RecordingSubscriber healthy = new RecordingSubscriber(10);
        reference.store.changeFeed(Basis.NONE).subscribe(thrower);
        reference.store.changeFeed(Basis.NONE).subscribe(healthy);
        assertThat(thrower.next().basis()).as("the change that the subscriber throws at").isEqualTo(new Basis(1));
        assertThat(healthy.next(2)).hasSize(2);

        Receipt further = reference.commit(Transaction.by(PRINCIPAL)
                .create("Person", from(1, 1), values("name", "Bob")).build());

        assertThat(further.basis()).as("the commit succeeded").isEqualTo(reference.store.latestBasis());
        assertThat(healthy.next().basis()).as("the store goes on").isEqualTo(further.basis());
        assertThat(thrower.calls()).as("the subscriber got the change that it threw at and nothing more").isEqualTo(1);
        assertThat(thrower.isFailed()).as("the exception goes nowhere").isFalse();
        assertThat(thrower.isCompleted()).isFalse();
        healthy.subscription().cancel();
    }

    @Test
    @DisplayName("Scenario: Closing completes subscriptions")
    void closingCompletesSubscriptions() {
        TestClock clock = new TestClock();
        DurableStore store = openDurable(clock);
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        RecordingSubscriber requesting = new RecordingSubscriber(1);
        RecordingSubscriber idle = new RecordingSubscriber(0);
        store.changeFeed(Basis.NONE).subscribe(requesting);
        store.changeFeed(new Basis(2)).subscribe(idle);
        assertThat(requesting.next().basis()).isEqualTo(new Basis(1));

        store.close();

        requesting.awaitCompleted();
        idle.awaitCompleted();
        assertThat(requesting.isFailed()).isFalse();
        assertThat(requesting.calls()).as("only what was requested").isEqualTo(1);
        assertThatThrownBy(() -> store.changeFeed(Basis.NONE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.changes(Basis.NONE, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitsDoNotWaitForASubscriberThatIsBlockedInOnNext() throws Exception {
        reference.createParties();
        CountDownLatch inOnNext = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingSubscriber blocked = new RecordingSubscriber(Long.MAX_VALUE, change -> {
            inOnNext.countDown();
            try {
                release.await(RecordingSubscriber.TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reference.store.changeFeed(Basis.NONE).subscribe(blocked);
        assertThat(inOnNext.await(RecordingSubscriber.TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // Commits, a schema registration and a purge all return while the subscriber is stuck.
        CompletableFuture<Long> commits = CompletableFuture.supplyAsync(() -> {
            for (int i = 0; i < 5; i++) {
                reference.commit(Transaction.by(PRINCIPAL).create("Person", from(1, 1), values("name", "P" + i))
                        .build());
            }
            return reference.store.latestBasis().value();
        });
        assertThat(commits.get(RecordingSubscriber.TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(7);

        release.countDown();
        assertThat(blocked.next(7)).extracting(change -> change.basis().value()).containsExactly(1L, 2L, 3L, 4L, 5L,
                6L, 7L);
        blocked.subscription().cancel();
    }

    // ------------------------------------------------------------------ changes after a purge

    @Test
    @DisplayName("Scenario: A purged person leaves the feed")
    void aPurgedPersonLeavesTheFeed() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        Receipt employment = reference.replay(ReferenceStories.rd3Employment(beta, alice));
        RelationshipId employs = relationship(employment, BETA_EMPLOYS_ALICE);
        Basis beforePurge = reference.store.latestBasis();

        PurgeResult result = reference.store.purge(ReferenceStories.rd11PurgeAlice(alice));

        assertThat(result).isInstanceOf(PurgeResult.Purged.class);
        assertThat(((PurgeResult.Purged) result).relationships()).containsExactly(employs);
        List<Change> changes = reference.store.changes(Basis.NONE, 100);
        assertThat(changes).extracting(Change::basis).containsExactly(new Basis(1), parties.basis(),
                employment.basis(), new Basis(beforePurge.value() + 1));
        TransactionChange rd1 = transaction(changes.get(1));
        assertThat(rd1.changed()).extracting(Changed::id).containsExactlyInAnyOrder(acme, beta);
        assertThat(rd1.transaction()).isEqualTo(reference.store.transactions().getFirst());
        TransactionChange rd3 = transaction(changes.get(2));
        assertThat(rd3.changed()).isEmpty();
        assertThat(rd3.transaction()).as("the record stays").isEqualTo(reference.store.transactions().get(1));
        assertThat(changes.getLast()).isEqualTo(new PurgeChange(reference.store.purges().getFirst()));
        assertThat(changes.stream().filter(TransactionChange.class::isInstance).map(TransactionChange.class::cast)
                .flatMap(change -> change.changed().stream()).map(Changed::id)).doesNotContain(alice, employs);
    }

    // ------------------------------------------------------------------ changes after reopening

    @Test
    @DisplayName("Scenario: The same changes after reopening")
    void theSameChangesAfterReopening() {
        TestClock clock = new TestClock();
        DurableStore first = openDurable(clock);
        assertThat(first.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);
        rd1ToRd5(first, clock);
        List<Change> before = first.changes(Basis.NONE, 100);
        assertThat(before).hasSize(9);
        first.close();

        DurableStore reopened = openDurable(clock);

        assertThat(ComparableChanges.of(reopened.changes(Basis.NONE, 100))).isEqualTo(ComparableChanges.of(before));
        assertThat(ComparableChanges.of(reopened.changes(new Basis(4), 2)))
                .isEqualTo(ComparableChanges.of(before.subList(4, 6)));
    }
}
