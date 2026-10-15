package io.github.enricoag1982.cairn.rocksdb;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Change;
import io.github.enricoag1982.cairn.data.Changed;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeChange;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.SchemaChange;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionChange;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The changes of random operations match what views read, in every kind of store (ADR-0029, design D2, D4 and D6 of
 * {@code add-change-feed}). An in-memory store, a store kept in a directory and a RocksDB store get the same random
 * creations, patches, replaces, end-dates (with their cascades), relationships, purges and schema registrations, and the
 * two durable stores are written to snapshot files, closed and opened again now and then. At the end of each run:
 *
 * <ul>
 *   <li>the three stores give the same changes, one for each basis;
 *   <li>for every change of a data transaction, at every effective time that matters (every date of the operations, and
 *       one microsecond before and after it, and the bounds of the periods that the change lists), an entity or
 *       relationship is listed with a period that holds the time exactly if a view at the change's basis and a view at the
 *       basis before read different versions of it, absent included; it is listed with the type that the view names and
 *       as created exactly if a view at the basis before knew nothing of it, and in the order of the text of the IDs;
 *   <li>what a purge removed is listed by no change;
 *   <li>a subscription made at the start delivered every change that was read, in order, once.
 * </ul>
 *
 * <p>The in-memory store is checked against every entity and relationship ever created; the durable stores against those
 * that each change lists and a sample of the others, which keeps the run short.
 */
class ChangeFeedEquivalenceTest {

    private static final int RUNS = 6;
    private static final int STEPS = 60;
    private static final int SAMPLED_OTHERS = 6;

    /** The dates that the operations use. */
    private static final List<Instant> DATES = List.of(on(1, 1), on(2, 1), on(3, 1), on(4, 1), on(6, 1), on(9, 1),
            on(10, 1), on(12, 1), on(2027, 1, 1), on(2027, 3, 1));

    @TempDir
    Path directory;

    /** The version of an entity or relationship that a view reads: what makes it that version, not its period. */
    private record Version(Map<String, Value> values, Instant recordedAt, Basis recordedBasis, Optional<EntityId> source,
            String type) {

        /** The version without the name of its type, which belongs to the schema and not to the version. */
        Version withoutType() {
            return new Version(values, recordedAt, recordedBasis, source, "");
        }
    }

    private static StoreSettings settings(TestClock clock) {
        return StoreSettings.builder().clock(clock).idGenerator(IdGenerators.deterministic())
                .rules(ReferenceModel.executableRules()).declaredRules(ReferenceModel.declaredRules()).build();
    }

    /** A subscription from the first basis with unbounded demand, which keeps what it is given. */
    private static final class Subscription implements Flow.Subscriber<Change> {

        final Store store;
        final List<Change> delivered = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        private volatile Flow.Subscription subscription;

        Subscription(Store store) {
            this.store = store;
            store.changeFeed(Basis.NONE).subscribe(this);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Change change) {
            delivered.add(change);
        }

        @Override
        public void onError(Throwable throwable) {
            failed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        void cancel() {
            subscription.cancel();
        }

        void awaitCompleted() throws InterruptedException {
            assertThat(completed.await(30, TimeUnit.SECONDS)).as("the subscription completed").isTrue();
        }

        /** Waits until the subscription has delivered the change of a basis. */
        void awaitDelivered(Basis basis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((delivered.isEmpty() || delivered.getLast().basis().compareTo(basis) < 0)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(delivered).as("delivered up to basis %d", basis.value()).isNotEmpty();
            assertThat(delivered.getLast().basis()).isEqualTo(basis);
        }
    }

    /** The stores of one run, what the operations created, and the operations. */
    private final class Run {

        final long seed;
        final Random random;
        final TestClock memoryClock = new TestClock();
        final TestClock logClock = new TestClock();
        final Path logDirectory;
        final RocksDbFixture rocks;
        final Store memory = Stores.inMemory(settings(memoryClock));
        DurableStore log;
        DurableStore rocksStore;

        final List<EntityId> persons = new ArrayList<>();
        final List<EntityId> organizations = new ArrayList<>();
        final List<EntityId> addresses = new ArrayList<>();
        final List<RelationshipId> employs = new ArrayList<>();
        final List<RelationshipId> owns = new ArrayList<>();
        final List<Id> everything = new ArrayList<>();
        final Set<Id> purged = new LinkedHashSet<>();
        final Map<Id, Instant> startedAt = new HashMap<>();
        final List<Subscription> subscriptions = new ArrayList<>();
        int accepted;
        int purges;
        int reopenings;
        int registrations;

        Run(long seed) {
            this.seed = seed;
            this.random = new Random(seed);
            this.logDirectory = directory.resolve("log-" + seed);
            this.rocks = new RocksDbFixture(directory.resolve("rocks-" + seed));
            this.log = Stores.durable(logDirectory, settings(logClock));
            this.rocksStore = rocks.open();
            for (Store store : stores()) {
                assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                        .isInstanceOf(RegistrationResult.Applied.class);
                subscriptions.add(new Subscription(store));
            }
        }

        List<Store> stores() {
            return List.of(memory, log, rocksStore);
        }

        // -------------------------------------------------------- operations

        void step(int number) throws InterruptedException {
            switch (random.nextInt(20)) {
                case 0, 1, 2 -> createParty("Person", persons, number);
                case 3 -> createParty("Organization", organizations, number);
                case 4 -> createAddress();
                case 5, 6, 7 -> patchParty(number);
                case 8 -> replaceParty(number);
                case 9, 10 -> endParty();
                case 11 -> relateEmploys();
                case 12 -> relateOwns();
                case 13 -> patchRelationship();
                case 14 -> endRelationship();
                case 15, 16 -> purge(number);
                case 17 -> registerSchema();
                case 18 -> reopen();
                default -> rejectedAndDry();
            }
        }

        /** A date at or after a time. */
        Instant dateFrom(Instant earliest) {
            List<Instant> later = DATES.stream().filter(date -> !date.isBefore(earliest)).toList();
            return later.get(random.nextInt(later.size()));
        }

        EffectivePeriod period(Instant from) {
            if (random.nextInt(4) != 0) {
                return EffectivePeriod.from(from);
            }
            List<Instant> later = DATES.stream().filter(date -> date.isAfter(from)).toList();
            return later.isEmpty() ? EffectivePeriod.from(from)
                    : EffectivePeriod.between(from, later.get(random.nextInt(later.size())));
        }

        <T extends Id> T pick(List<T> candidates) {
            List<T> live = candidates.stream().filter(id -> !purged.contains(id)).toList();
            return live.isEmpty() ? null : live.get(random.nextInt(live.size()));
        }

        EntityId party() {
            List<EntityId> parties = new ArrayList<>(persons);
            parties.addAll(organizations);
            return pick(parties);
        }

        /** Commits a transaction to every store, which must answer alike, and tells the receipt if it was accepted. */
        Optional<Receipt> commit(Transaction transaction) {
            CommitResult expected = memory.commit(transaction);
            assertThat(log.commit(transaction)).as("seed %d, store kept through a log", seed).isEqualTo(expected);
            assertThat(rocksStore.commit(transaction)).as("seed %d, RocksDB store", seed).isEqualTo(expected);
            if (expected instanceof CommitResult.Committed committed) {
                accepted++;
                return Optional.of(committed.receipt());
            }
            return Optional.empty();
        }

        void remember(Id id, Instant from) {
            everything.add(id);
            startedAt.put(id, from);
        }

        void createParty(String type, List<EntityId> into, int number) {
            Instant from = DATES.get(random.nextInt(3));
            commit(Transaction.by(PRINCIPAL).create(type, EffectivePeriod.from(from),
                    values("name", type + " " + number)).build()).ifPresent(receipt -> {
                        EntityId id = (EntityId) receipt.created().getFirst();
                        into.add(id);
                        remember(id, from);
                    });
        }

        void createAddress() {
            EntityId owner = party();
            if (owner == null) {
                return;
            }
            Instant from = startedAt.get(owner);
            commit(Transaction.by(PRINCIPAL)
                    .create(new TemporaryId("home"), "Address", EffectivePeriod.from(from),
                            values("street", "1 Main St", "city", "Springfield", "country", "US"))
                    .relate(new TemporaryId("hasAddress"), "HasAddress", owner, new TemporaryId("home"),
                            EffectivePeriod.from(from), values())
                    .build()).ifPresent(receipt -> {
                        for (Id id : receipt.created()) {
                            remember(id, from);
                            if (id instanceof EntityId address) {
                                addresses.add(address);
                            }
                        }
                    });
        }

        void patchParty(int number) {
            EntityId target = party();
            if (target != null) {
                commit(Transaction.by(PRINCIPAL).reason("patch " + number)
                        .patch(target, period(dateFrom(startedAt.get(target))), values("name", "Renamed " + number))
                        .build());
            }
        }

        void replaceParty(int number) {
            EntityId target = party();
            if (target != null) {
                commit(Transaction.by(PRINCIPAL)
                        .replace(target, period(dateFrom(startedAt.get(target))), values("name", "Replaced " + number))
                        .build());
            }
        }

        void endParty() {
            EntityId target = random.nextInt(5) == 0 ? pick(addresses) : party();
            if (target != null) {
                commit(Transaction.by(PRINCIPAL).endDate(target, dateFrom(startedAt.get(target))).build());
            }
        }

        void relateEmploys() {
            EntityId employer = pick(organizations);
            EntityId employee = pick(persons);
            if (employer == null || employee == null) {
                return;
            }
            Instant from = dateFrom(later(startedAt.get(employer), startedAt.get(employee)));
            commit(Transaction.by(PRINCIPAL).relate("Employs", employer, employee, period(from),
                    values("title", "Analyst " + random.nextInt(3))).build()).ifPresent(receipt -> {
                        RelationshipId id = (RelationshipId) receipt.created().getFirst();
                        employs.add(id);
                        remember(id, from);
                    });
        }

        void relateOwns() {
            EntityId owner = pick(organizations);
            EntityId owned = party();
            if (owner == null || owned == null) {
                return;
            }
            Instant from = dateFrom(later(startedAt.get(owner), startedAt.get(owned)));
            commit(Transaction.by(PRINCIPAL).relate("Owns", owner, owned, period(from),
                    values("share", 10 + random.nextInt(30))).build()).ifPresent(receipt -> {
                        RelationshipId id = (RelationshipId) receipt.created().getFirst();
                        owns.add(id);
                        remember(id, from);
                    });
        }

        private Instant later(Instant first, Instant second) {
            return first.isAfter(second) ? first : second;
        }

        void patchRelationship() {
            RelationshipId employment = pick(employs);
            if (employment != null) {
                commit(Transaction.by(PRINCIPAL).patch(employment, period(dateFrom(startedAt.get(employment))),
                        values("title", "Lead " + random.nextInt(3))).build());
            }
            RelationshipId ownership = pick(owns);
            if (ownership != null) {
                commit(Transaction.by(PRINCIPAL).patch(ownership, period(dateFrom(startedAt.get(ownership))),
                        values("share", 5 + random.nextInt(20))).build());
            }
        }

        void endRelationship() {
            RelationshipId target = pick(random.nextBoolean() ? employs : owns);
            if (target != null) {
                commit(Transaction.by(PRINCIPAL).endDate(target, dateFrom(startedAt.get(target))).build());
            }
        }

        void purge(int number) {
            List<EntityId> candidates = new ArrayList<>(persons);
            candidates.addAll(organizations);
            EntityId target = pick(candidates);
            if (target == null) {
                return;
            }
            Purge purge = Purge.by("dpo").reason("erasure " + number).entity(target).build();
            PurgeResult expected = memory.purge(purge);
            assertThat(log.purge(purge)).as("seed %d, store kept through a log", seed).isEqualTo(expected);
            assertThat(rocksStore.purge(purge)).as("seed %d, RocksDB store", seed).isEqualTo(expected);
            if (expected instanceof PurgeResult.Purged done) {
                purges++;
                purged.addAll(done.entities());
                purged.addAll(done.relationships());
            }
        }

        void registerSchema() {
            registrations++;
            Origin origin = new Origin("extras", "1." + registrations);
            int attributes = registrations;
            SchemaDefinition definition = SchemaDefinition.builder().entityType("Extra", extra ->
                    IntStream.rangeClosed(1, attributes).forEach(i -> extra.attribute("a" + i, ValueType.STRING)))
                    .build();
            for (Store store : stores()) {
                assertThat(store.schema().register(origin, definition)).isInstanceOf(RegistrationResult.Applied.class);
            }
        }

        /** Writes snapshot files now and then, and closes the two durable stores and opens them again. */
        void reopen() throws InterruptedException {
            if (random.nextBoolean()) {
                log.writeSnapshot();
                rocksStore.writeSnapshot();
            }
            checkAgainstMemory("before reopening, seed " + seed);
            log.close();
            rocksStore.close();
            List<Subscription> closed = subscriptions.stream().filter(subscription -> subscription.store != memory)
                    .toList();
            for (Subscription subscription : closed) {
                subscription.awaitCompleted();
                assertThat(ComparableChanges.of(withoutPurged(subscription.delivered, purged)))
                        .as("what a closed store delivered, seed %d", seed)
                        .isEqualTo(ComparableChanges.of(changesOf(memory)).subList(0, subscription.delivered.size()));
            }
            subscriptions.removeAll(closed);
            log = Stores.durable(logDirectory, settings(logClock));
            rocksStore = rocks.open();
            subscriptions.add(new Subscription(log));
            subscriptions.add(new Subscription(rocksStore));
            reopenings++;
            checkAgainstMemory("after reopening, seed " + seed);
        }

        /** A transaction that is dry run, and one that is rejected: neither takes a basis. */
        void rejectedAndDry() {
            Basis before = memory.latestBasis();
            for (Store store : stores()) {
                store.dryRun(Transaction.by(PRINCIPAL).create("Person", EffectivePeriod.from(on(1, 1)),
                        values("name", "Dry")).build());
                assertThat(store.commit(Transaction.by(PRINCIPAL).create("Person", EffectivePeriod.from(on(1, 1)),
                        values("name", 5)).build())).isInstanceOf(CommitResult.Rejected.class);
            }
            assertThat(memory.latestBasis()).isEqualTo(before);
        }

        void checkAgainstMemory(String where) {
            List<Object> expected = ComparableChanges.of(changesOf(memory));
            assertThat(ComparableChanges.of(changesOf(log))).as("store kept through a log, %s", where)
                    .isEqualTo(expected);
            assertThat(ComparableChanges.of(changesOf(rocksStore))).as("RocksDB store, %s", where).isEqualTo(expected);
        }

        void closeAll() {
            subscriptions.forEach(Subscription::cancel);
            log.close();
            rocks.closeAll();
        }
    }

    private static List<Change> changesOf(Store store) {
        return store.changes(Basis.NONE, Integer.MAX_VALUE);
    }

    /**
     * Changes as a consumer holds them without what a purge has removed since: a change that was delivered stays with the
     * consumer as it was (ADR-0024, ADR-0029), while the store forgets the purged entities and relationships in it.
     */
    private static List<Change> withoutPurged(List<Change> delivered, Set<Id> purged) {
        return delivered.stream().map(change -> change instanceof TransactionChange transaction
                ? (Change) new TransactionChange(transaction.transaction(), transaction.changed().stream()
                        .filter(changed -> !purged.contains(changed.id())).toList())
                : change).toList();
    }

    // ------------------------------------------------------------------ the test

    @Test
    void theChangesOfRandomOperationsMatchWhatViewsReadInEveryKindOfStore() throws InterruptedException {
        int accepted = 0;
        int purges = 0;
        int reopenings = 0;
        int registrations = 0;
        for (long seed = 1; seed <= RUNS; seed++) {
            Run run = new Run(seed);
            try {
                for (int step = 0; step < STEPS; step++) {
                    run.step(step);
                }
                finish(run);
            } finally {
                run.closeAll();
            }
            assertThat(run.accepted).as("seed %d accepted a good many commits", seed).isGreaterThan(15);
            accepted += run.accepted;
            purges += run.purges;
            reopenings += run.reopenings;
            registrations += run.registrations;
        }
        assertThat(purges).as("purges, over all runs").isGreaterThan(RUNS / 2);
        assertThat(reopenings).as("reopenings, over all runs").isGreaterThan(RUNS / 2);
        assertThat(registrations).as("schema registrations, over all runs").isGreaterThan(RUNS / 2);
        assertThat(accepted).isGreaterThan(RUNS * 15);
    }

    private void finish(Run run) throws InterruptedException {
        run.checkAgainstMemory("at the end, seed " + run.seed);
        Basis latest = run.memory.latestBasis();
        for (Store store : run.stores()) {
            List<Change> changes = changesOf(store);
            String where = "seed " + run.seed + ", " + (store == run.memory ? "in-memory store"
                    : store == run.log ? "store kept through a log" : "RocksDB store");
            assertThat(changes).as("%s: one change for each basis", where).hasSize((int) latest.value());
            for (int i = 0; i < changes.size(); i++) {
                assertThat(changes.get(i).basis()).isEqualTo(new Basis(i + 1L));
            }
            assertThat(chunks(store, 5)).as("%s: read in chunks of five", where).isEqualTo(changes);
            verifyAgainstViews(store, changes, run, where, store == run.memory);
        }
        for (Subscription subscription : run.subscriptions) {
            subscription.awaitDelivered(latest);
            assertThat(ComparableChanges.of(withoutPurged(subscription.delivered, run.purged)))
                    .as("seed %d, what a subscription delivered", run.seed)
                    .isEqualTo(ComparableChanges.of(changesOf(subscription.store)));
            assertThat(subscription.failed.getCount()).as("it did not fail").isEqualTo(1);
        }
        assertThat(changesOf(run.memory)).filteredOn(PurgeChange.class::isInstance).hasSize(run.purges);
        assertThat(changesOf(run.memory)).filteredOn(SchemaChange.class::isInstance).hasSize(1 + run.registrations);
    }

    private static List<Change> chunks(Store store, int limit) {
        List<Change> all = new ArrayList<>();
        Basis cursor = Basis.NONE;
        while (true) {
            List<Change> chunk = store.changes(cursor, limit);
            if (chunk.isEmpty()) {
                return all;
            }
            assertThat(chunk).hasSizeLessThanOrEqualTo(limit);
            all.addAll(chunk);
            cursor = chunk.getLast().basis();
        }
    }

    // ------------------------------------------------------------------ views

    /** What a view reads of an entity or relationship: its version, or nothing. */
    private static Optional<Version> version(View view, Id id) {
        return switch (id) {
            case EntityId entity -> view.entity(entity).map(state -> new Version(state.values(), state.recordedAt(),
                    state.recordedBasis(), Optional.empty(), state.entityType()));
            case RelationshipId relationship -> view.relationship(relationship).map(state -> new Version(
                    state.values(), state.recordedAt(), state.recordedBasis(), Optional.of(state.source()),
                    state.relationshipType()));
        };
    }

    /** The effective times that matter: every date, and one microsecond before and after it. */
    private static List<Instant> samples() {
        TreeSet<Instant> times = new TreeSet<>();
        times.add(on(2025, 12, 1));
        times.add(on(2027, 6, 1));
        for (Instant date : DATES) {
            times.add(date);
            times.add(date.minus(1, ChronoUnit.MICROS));
            times.add(date.plus(1, ChronoUnit.MICROS));
        }
        return List.copyOf(times);
    }

    private void verifyAgainstViews(Store store, List<Change> changes, Run run, String where, boolean exhaustive) {
        List<Instant> samples = samples();
        Set<Id> mentioned = new LinkedHashSet<>();
        int transactionChanges = 0;
        for (Change change : changes) {
            if (!(change instanceof TransactionChange transaction)) {
                continue;
            }
            transactionChanges++;
            long basis = change.basis().value();
            assertThat(transaction.transaction().basis()).isEqualTo(change.basis());
            Map<Id, Changed> listed = new HashMap<>();
            String previous = null;
            for (Changed changed : transaction.changed()) {
                assertThat(listed.put(changed.id(), changed)).as("%s lists %s once", where, changed.id()).isNull();
                assertThat(previous == null || previous.compareTo(changed.id().value()) < 0)
                        .as("%s, basis %d: ordered by the text of the IDs", where, basis).isTrue();
                previous = changed.id().value();
                mentioned.add(changed.id());
            }
            List<Id> ids = new ArrayList<>(exhaustive ? run.everything : listed.keySet());
            if (!exhaustive) {
                for (int i = 0; i < SAMPLED_OTHERS && !run.everything.isEmpty(); i++) {
                    Id other = run.everything.get(run.random.nextInt(run.everything.size()));
                    if (!ids.contains(other)) {
                        ids.add(other);
                    }
                }
            }
            TreeSet<Instant> times = new TreeSet<>(samples);
            for (Changed changed : transaction.changed()) {
                for (EffectivePeriod period : changed.periods()) {
                    times.add(period.from());
                    times.add(period.from().minus(1, ChronoUnit.MICROS));
                    period.until().ifPresent(until -> {
                        times.add(until);
                        times.add(until.minus(1, ChronoUnit.MICROS));
                    });
                }
            }
            Map<Id, String> typeAfter = new HashMap<>();
            for (Instant time : times) {
                View before = store.view(Coordinates.effectiveAt(time).knownAt(new Basis(basis - 1)));
                View after = store.view(Coordinates.effectiveAt(time).knownAt(new Basis(basis)));
                for (Id id : ids) {
                    Optional<Version> was = version(before, id);
                    Optional<Version> is = version(after, id);
                    is.ifPresent(version -> typeAfter.putIfAbsent(id, version.type()));
                    // Versions are compared without their type name: a name is the schema's, not the version's.
                    boolean differs = !was.map(Version::withoutType).equals(is.map(Version::withoutType));
                    Changed changed = listed.get(id);
                    boolean inPeriods = changed != null
                            && changed.periods().stream().anyMatch(period -> period.contains(time));
                    assertThat(inPeriods).as("%s, basis %d, %s at %s: %s", where, basis, id, time,
                            differs ? "the views read other versions, so it is listed with a period that holds the time"
                                    : "the views read the same version, so no period holds the time")
                            .isEqualTo(differs);
                }
            }
            for (Changed changed : transaction.changed()) {
                assertThat(changed.type()).as("%s, basis %d, the type of %s", where, basis, changed.id())
                        .isEqualTo(typeAfter.get(changed.id()));
                View earlier = store.view(Coordinates.effectiveAt(changed.periods().getFirst().from())
                        .knownAt(new Basis(basis - 1)));
                boolean knownBefore = switch (changed.id()) {
                    case EntityId entity -> !earlier.timeline(entity).isEmpty();
                    case RelationshipId relationship -> !earlier.relationshipTimeline(relationship).isEmpty();
                };
                assertThat(changed.created()).as("%s, basis %d, %s created", where, basis, changed.id())
                        .isEqualTo(!knownBefore);
            }
        }
        assertThat(transactionChanges).as("%s: something was committed", where).isPositive();
        assertThat(mentioned).as("%s: a purged ID is named by no change", where).doesNotContainAnyElementsOf(run.purged);
    }
}
