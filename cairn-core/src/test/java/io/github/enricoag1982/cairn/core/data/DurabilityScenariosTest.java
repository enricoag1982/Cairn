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
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.DurableStore;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scenarios of the {@code durability} spec, one test each. */
class DurabilityScenariosTest {

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

    private StoreEquivalence.Observation observe(DirectoryStore store, ReferenceTales.Told told) {
        return StoreEquivalence.observe(store, told.ids(), EFFECTIVE_TIMES, List.of());
    }

    // ------------------------------------------------------------------ durable stores

    @Test
    @DisplayName("Scenario: Reopening a store")
    void reopeningAStore() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        StoreEquivalence.Observation before = observe(first, told);
        first.close();

        DirectoryStore reopened = durable.open();

        StoreEquivalence.assertEquivalent(before, observe(reopened, told));
        assertThat(reopened.schema().history()).as("the schema's history").hasSize(1);
        assertThat(reopened.latestBasis()).isEqualTo(new Basis(9));
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 15))).entities("Party")).as("a view reads").hasSize(3);
    }

    @Test
    @DisplayName("Scenario: The next commit after reopening")
    void theNextCommitAfterReopening() {
        DirectoryStore first = durable.openWithReferenceModel();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        durable.replay(first, ReferenceStories.rd5Accounts(beta, acme));
        durable.replay(first, ReferenceStories.rd2Ownership(acme, beta));
        durable.replay(first, ReferenceStories.rd3Employment(beta, entity(parties, ALICE)));
        durable.replay(first, ReferenceStories.rd4CustomerRole(beta));
        durable.replay(first, ReferenceStories.rd9AcmeLei(acme));
        assertThat(first.latestBasis()).as("the basis before closing").isEqualTo(new Basis(7));
        first.close();

        DirectoryStore reopened = durable.open();
        Receipt receipt = DurableFixture.commit(reopened,
                Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob")).build());

        assertThat(reopened.latestBasis()).isEqualTo(new Basis(8));
        assertThat(receipt.basis()).isEqualTo(new Basis(8));
    }

    @Test
    @DisplayName("Scenario: An idempotency key after reopening")
    void anIdempotencyKeyAfterReopening() {
        DirectoryStore first = durable.openWithReferenceModel();
        Transaction transaction = Transaction.by("importer").idempotencyKey("import-42")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build();
        Receipt receipt = DurableFixture.commit(first, transaction);
        first.close();

        DirectoryStore reopened = durable.open();
        CommitResult again = reopened.commit(transaction);

        assertThat(again).isEqualTo(new CommitResult.Committed(receipt));
        assertThat(reopened.latestBasis()).as("nothing is recorded").isEqualTo(receipt.basis());
        assertThat(reopened.transactions()).hasSize(1);
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).hasSize(1);
    }

    // ------------------------------------------------------------------ recovery repeats what was committed

    @Test
    @DisplayName("Scenario: Recovering without the code of a rule")
    void recoveringWithoutTheCodeOfARule() {
        DirectoryStore first = durable.openWithReferenceModel();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        durable.replay(first, ReferenceStories.rd9AcmeLei(acme));
        first.close();

        DirectoryStore reopened = durable.open(durable.clock, IdGenerators.deterministic(),
                List.of(new TotalOwnershipRule()), List.of(LeiChecksumRule.DESCRIPTOR));
        List<Problem> problems = durable.rejectReplay(reopened, ReferenceStories.rd7Rename(acme));

        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entity(acme)).get()
                .satisfies(state -> assertThat(state.values()).containsEntry("lei",
                        Value.of(ReferenceStories.LEI)));
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
            assertThat(problem.message()).contains("lei-checksum");
        });
    }

    @Test
    @DisplayName("Scenario: A clock that went back")
    void aClockThatWentBack() {
        DirectoryStore first = durable.openWithReferenceModel();
        durable.clock.moveTo(on(3, 1));
        Receipt last = DurableFixture.commit(first,
                Transaction.by("importer").create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());
        first.close();

        TestClock earlier = new TestClock();
        earlier.moveTo(on(1, 1));
        DirectoryStore reopened = durable.open(earlier, IdGenerators.deterministic(), ReferenceModel.executableRules(),
                ReferenceModel.declaredRules());
        Receipt receipt = DurableFixture.commit(reopened,
                Transaction.by("importer").create(BETA, "Organization", from(1, 1), values("name", "Beta")).build());

        assertThat(earlier.instant()).isBefore(last.transactionTime());
        assertThat(receipt.transactionTime()).isAfter(last.transactionTime());
    }

    @Test
    @DisplayName("Scenario: Another ID generator")
    void anotherIdGenerator() {
        DirectoryStore first = durable.open(durable.clock, IdGenerators.uuidV7(durable.clock, new Random(1)),
                ReferenceModel.executableRules(), ReferenceModel.declaredRules());
        DurableFixture.registerReferenceModel(first);
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        List<String> held = told.ids().stream().map(Id::value).toList();
        first.close();

        assertThatThrownBy(() -> durable.open(durable.clock, new Accepting(text -> text.startsWith("other-")),
                ReferenceModel.executableRules(), ReferenceModel.declaredRules()))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(held).as("it names an ID that the store holds")
                        .anyMatch(id -> e.getMessage().contains(id)));

        DirectoryStore again = durable.open(durable.clock, IdGenerators.uuidV7(durable.clock, new Random(2)),
                ReferenceModel.executableRules(), ReferenceModel.declaredRules());
        assertThat(again.latestBasis()).as("the directory was released, and nothing was changed").isEqualTo(
                new Basis(9));
    }

    @Test
    void anIdGeneratorThatRejectsOnlyTheIdOfARelationshipIsRefusedNamingIt() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        Id relationship = told.ids().stream().filter(id -> id instanceof RelationshipId).findFirst().orElseThrow();
        first.close();

        assertThatThrownBy(() -> durable.open(durable.clock, new Accepting(text -> !text.equals(relationship.value())),
                ReferenceModel.executableRules(), ReferenceModel.declaredRules()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(relationship.value())
                .hasMessageContaining("relationship");
    }

    /** An ID generator that accepts the texts that a predicate accepts, and is never asked for an ID. */
    private record Accepting(Predicate<String> accepting) implements IdGenerator {

        @Override
        public String next() {
            throw new AssertionError("Recovery asked the ID generator.");
        }

        @Override
        public boolean accepts(String text) {
            return accepting.test(text);
        }

        @Override
        public IdGenerator copy() {
            return this;
        }
    }

    @Test
    @DisplayName("Scenario: Re-checks after reopening")
    void rechecksAfterReopening() {
        DirectoryStore first = durable.openWithEmployerHoldsAccount();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt accounts = durable.replay(first, ReferenceStories.rd5Accounts(beta, acme));
        Receipt employment = durable.replay(first, ReferenceStories.rd3Employment(beta, entity(parties, ALICE)));
        Transaction movesA1 = Transaction.by("importer")
                .endDate(ReferenceTales.relationship(accounts, HELD_BY_A1), on(6, 1))
                .relate(HELD_BY_A1_MOVED, "HeldBy", entity(accounts, A1), acme, from(6, 1), values()).build();
        durable.clock.moveTo(on(5, 20));
        List<Problem> before = ((DryRunResult.Rejected) first.dryRun(movesA1)).problems();
        first.close();

        DirectoryStore reopened = durable.reopenWithEmployerHoldsAccount();
        CommitResult result = reopened.commit(movesA1);

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        List<Problem> problems = ((CommitResult.Rejected) result).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
            assertThat(problem.ruleCode()).contains("employer-holds-account/no-account");
            assertThat(problem.path()).isEqualTo(ReferenceTales.relationship(employment,
                    ReferenceStories.BETA_EMPLOYS_ALICE).value());
        });
        assertThat(problems).as("as before the store was closed").isEqualTo(before);
    }

    @Test
    @DisplayName("Scenario: IDs after reopening")
    void idsAfterReopening() {
        DirectoryStore first = durable.openWithReferenceModel();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        first.close();

        DirectoryStore reopened = durable.open();
        Receipt receipt = DurableFixture.commit(reopened, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build());

        assertThat(parties.created()).hasSize(3);
        assertThat(receipt.created()).singleElement().isNotIn(parties.created());
    }

    // ------------------------------------------------------------------ crash safety

    @Test
    @DisplayName("Scenario: A commit cut short")
    void aCommitCutShort() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        StoreEquivalence.Observation beforeTheLast = observe(first, told);
        Receipt last = durable.replay(first, ReferenceStories.rd7Rename(entity(told.parties, ACME)));
        DurableFixture.crash(first);
        Path segment = StoreFiles.lastSegment(directory);
        long lastRecord = StoreFiles.recordStarts(segment).getLast();
        StoreFiles.truncate(segment, lastRecord + 20);

        DirectoryStore reopened = durable.open();

        assertThat(reopened.latestBasis()).isEqualTo(new Basis(last.basis().value() - 1));
        StoreEquivalence.assertEquivalent(beforeTheLast, observe(reopened, told));
        assertThat(DurableFixture.commit(reopened, Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build()).basis()).isEqualTo(last.basis());
    }

    @Test
    @DisplayName("Scenario: A last record full of zeros")
    void aLastRecordFullOfZeros() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        StoreEquivalence.Observation beforeTheLast = observe(first, told);
        Receipt last = durable.replay(first, ReferenceStories.rd7Rename(entity(told.parties, ACME)));
        DurableFixture.crash(first);
        Path segment = StoreFiles.lastSegment(directory);
        long lastRecord = StoreFiles.recordStarts(segment).getLast();
        long zerosFrom = lastRecord + StoreFiles.FRAME_HEADER + 10;
        StoreFiles.zeroFrom(segment, zerosFrom);
        assertThat(StoreFiles.size(segment)).as("the file is as long as it was").isGreaterThan(zerosFrom);

        DirectoryStore reopened = durable.open();

        assertThat(reopened.latestBasis()).isEqualTo(new Basis(last.basis().value() - 1));
        StoreEquivalence.assertEquivalent(beforeTheLast, observe(reopened, told));
        assertThat(StoreFiles.size(segment)).as("the record is gone from the log").isEqualTo(lastRecord);
    }

    @Test
    @DisplayName("Scenario: A damaged log")
    void aDamagedLog() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(first, durable.clock);
        DurableFixture.crash(first);
        Path segment = StoreFiles.lastSegment(directory);
        List<Long> starts = StoreFiles.recordStarts(segment);
        long damaged = starts.get(starts.size() - 3);
        StoreFiles.damage(segment, damaged + StoreFiles.FRAME_HEADER + 5);
        Map<String, byte[]> before = StoreFiles.contents(directory);

        assertThatThrownBy(() -> durable.open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segment.toString()).hasMessageContaining("byte " + damaged);

        assertThat(told.receipts).isNotEmpty();
        assertThat(StoreFiles.contents(directory)).as("the files are unchanged").containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).as(name).isEqualTo(before.get(name)));
    }

    @Test
    @DisplayName("Scenario: An unknown format version")
    void anUnknownFormatVersion() {
        DirectoryStore first = durable.openWithReferenceModel();
        ReferenceTales.rd1ToRd5(first, durable.clock);
        DurableFixture.crash(first);
        Path segment = StoreFiles.lastSegment(directory);
        StoreFiles.writeInt(segment, 8, 2);

        assertThatThrownBy(() -> durable.open()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segment.toString()).hasMessageContaining("version 2");
    }

    // ------------------------------------------------------------------ snapshot files

    @Test
    @DisplayName("Scenario: Recovering from a snapshot file and the log after it")
    void recoveringFromASnapshotFileAndTheLogAfterIt() {
        DirectoryStore first = durable.openWithReferenceModel();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        durable.replay(first, ReferenceStories.rd2Ownership(acme, beta));
        Basis snapshot = first.writeSnapshot();
        Receipt employment = durable.replay(first, ReferenceStories.rd3Employment(beta, entity(parties, ALICE)));
        List<Id> ids = new ArrayList<>(parties.created());
        ids.addAll(employment.created());
        StoreEquivalence.Observation before = StoreEquivalence.observe(first, ids, EFFECTIVE_TIMES, List.of());
        DurableFixture.crash(first);

        DirectoryStore reopened = durable.open();

        assertThat(snapshot).isEqualTo(new Basis(3));
        assertThat(StoreFiles.snapshots(directory)).hasSize(1);
        StoreEquivalence.assertEquivalent(before, StoreEquivalence.observe(reopened, ids, EFFECTIVE_TIMES, List.of()));
        assertThat(reopened.view(Coordinates.effectiveAt(on(3, 15))).navigate(beta, "Employs")).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: A damaged newest snapshot file")
    void aDamagedNewestSnapshotFile() {
        DirectoryStore first = durable.openWithReferenceModel();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Basis older = first.writeSnapshot();
        Receipt ownership = durable.replay(first, ReferenceStories.rd2Ownership(acme, beta));
        Basis newest = first.writeSnapshot();
        Receipt employment = durable.replay(first, ReferenceStories.rd3Employment(beta, entity(parties, ALICE)));
        List<Id> ids = new ArrayList<>(parties.created());
        ids.addAll(ownership.created());
        ids.addAll(employment.created());
        StoreEquivalence.Observation before = StoreEquivalence.observe(first, ids, EFFECTIVE_TIMES, List.of());
        DurableFixture.crash(first);
        Path newestFile = StoreFiles.snapshots(directory).getLast();
        StoreFiles.damage(newestFile, StoreFiles.size(newestFile) / 2);

        DirectoryStore reopened = durable.open();

        assertThat(StoreFiles.snapshots(directory)).as("two snapshot files were written").hasSize(2);
        assertThat(older).isEqualTo(new Basis(2));
        assertThat(newest).isEqualTo(new Basis(3));
        StoreEquivalence.assertEquivalent(before, StoreEquivalence.observe(reopened, ids, EFFECTIVE_TIMES, List.of()));
        assertThat(reopened.latestBasis()).isEqualTo(new Basis(4));
    }

    // ------------------------------------------------------------------ one store per directory

    @Test
    @DisplayName("Scenario: A directory in use")
    void aDirectoryInUse() {
        DurableStore first = Stores.durable(directory, StoreSettings.builder().clock(durable.clock).build());

        assertThatThrownBy(() -> Stores.durable(directory, StoreSettings.builder().clock(durable.clock).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(directory.toString());
        first.close();

        DurableStore second = Stores.durable(directory, StoreSettings.builder().clock(durable.clock).build());
        assertThat(second.latestBasis()).isEqualTo(Basis.NONE);
        second.close();
    }

    // ------------------------------------------------------------------ a commit that cannot be written

    @Test
    @DisplayName("Scenario: A failed write")
    void aFailedWrite() {
        DirectoryStore store = durable.openWithReferenceModel();
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        Basis before = store.latestBasis();
        store.breakLog();
        Transaction transaction = Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build();

        assertThatThrownBy(() -> store.commit(transaction)).isInstanceOf(UncheckedIOException.class);

        assertThat(store.latestBasis()).as("the transaction is not recorded").isEqualTo(before);
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Person")).as("nor visible")
                .extracting(state -> state.values().get("name")).doesNotContain(
                        Value.of("Bob"));
        assertThatThrownBy(() -> store.commit(transaction)).as("a further commit").isInstanceOf(
                IllegalStateException.class);
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Party")).as("views read the earlier state")
                .hasSize(parties.created().size());
    }
}
