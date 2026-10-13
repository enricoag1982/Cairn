package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.core.data.ReferenceTales.EFFECTIVE_TIMES;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
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
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A durable store beyond the scenarios of the {@code durability} spec (design D1, D2, D4, D5, D6 and D7 of
 * {@code add-durable-commit-log}): closing, what recovery does not use, schema identifiers, log segments and the two
 * newest snapshot files, and commits and snapshot files at the same time.
 */
class DurableStoreTest {

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

    private static Transaction newPerson(String name) {
        return Transaction.by("importer").create("Person", from(1, 1), values("name", name)).build();
    }

    // ------------------------------------------------------------------ closing

    @Test
    void closingWritesASnapshotFileOfTheLatestBasisAndReleasesTheDirectory() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.rd1ToRd5(store, durable.clock);

        store.close();

        assertThat(StoreFiles.snapshots(directory)).extracting(path -> path.getFileName().toString())
                .containsExactly("00000000000000000009.snapshot");
        durable.open();
    }

    @Test
    void closingTwiceDoesNothingTheSecondTime() {
        DirectoryStore store = durable.openWithReferenceModel();
        store.close();
        Map<String, byte[]> files = StoreFiles.contents(directory);

        store.close();

        assertThat(StoreFiles.contents(directory)).containsOnlyKeys(files.keySet());
    }

    @Test
    void aClosedStoreRefusesEveryCallButCloseAndSoDoesItsSchemaRegistry() {
        DirectoryStore store = durable.openWithReferenceModel();
        var registry = store.schema();
        store.close();

        assertThatThrownBy(() -> store.commit(newPerson("Bob"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.dryRun(newPerson("Bob"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.view(Coordinates.effectiveAt(on(1, 1)))).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(store::latestBasis).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::transactions).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::declaredRules).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::schema).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::writeSnapshot).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::current).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::history).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::availableRules).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(ReferenceModel.origin("1.1.0"), SchemaDefinition.EMPTY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.plan(ReferenceModel.origin("1.1.0"), SchemaDefinition.EMPTY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.validate(ReferenceModel.origin("1.1.0"), SchemaDefinition.EMPTY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.storedDefinition("reference-model"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.asOf(Basis.NONE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.asOf(Instant.EPOCH)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSnapshotThatCannotBeWrittenAtCloseStillReleasesTheDirectory() throws IOException {
        DirectoryStore store = durable.openWithReferenceModel();
        Files.createFile(directory.resolve("snapshots"));

        assertThatThrownBy(store::close).isInstanceOf(UncheckedIOException.class);

        Files.delete(directory.resolve("snapshots"));
        DirectoryStore reopened = durable.open();
        assertThat(reopened.latestBasis()).as("the registration is in the log").isEqualTo(new Basis(1));
    }

    @Test
    void writingASnapshotFileReturnsItsBasisAndLeavesNoTemporaryFile() {
        DirectoryStore store = durable.openWithReferenceModel();
        assertThat(store.writeSnapshot()).isEqualTo(new Basis(1));
        ReferenceTales.rd1ToRd5(store, durable.clock);

        assertThat(store.writeSnapshot()).isEqualTo(new Basis(9));

        assertThat(StoreFiles.snapshots(directory)).hasSize(2);
        assertThat(directory.resolve("snapshots").toFile().list()).noneMatch(name -> name.endsWith(".tmp"));
    }

    @Test
    void anEmptyStoreCanBeSnapshottedClosedAndOpenedAgain() {
        DirectoryStore store = durable.open();
        assertThat(store.writeSnapshot()).isEqualTo(Basis.NONE);
        store.close();

        DirectoryStore reopened = durable.open();

        assertThat(reopened.latestBasis()).isEqualTo(Basis.NONE);
        assertThat(reopened.transactions()).isEmpty();
        assertThat(reopened.schema().current().isEmpty()).isTrue();
    }

    @Test
    void openingAndClosingWithoutACommitReusesTheEmptySegment() {
        durable.open().close();
        durable.open().close();
        DirectoryStore third = durable.openWithReferenceModel();
        third.close();

        assertThat(StoreFiles.segments(directory)).hasSize(1);
        assertThat(durable.open().latestBasis()).isEqualTo(new Basis(1));
    }

    // ------------------------------------------------------------------ opening

    @Test
    void storesCreateTheirDirectoryAndTheDefaultSettingsWork(@TempDir Path other) {
        Path nested = other.resolve("a").resolve("b").resolve("store");

        DurableStore store = Stores.durable(nested);
        store.schema().register(new Origin("things", "1"), SchemaDefinition.builder().entityType("Thing",
                thing -> thing.attribute("name", ValueType.STRING)).build());
        Receipt receipt = DurableFixture.commit(store, Transaction.by("tester")
                .create("Thing", from(1, 1), values("name", "one")).build());
        store.close();

        assertThat(nested.resolve("log")).isDirectory();
        assertThat(nested.resolve("snapshots")).isDirectory();
        assertThat(nested.resolve("lock")).exists();
        DurableStore reopened = Stores.durable(nested);
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entity((EntityId) receipt.created().getFirst()))
                .isPresent();
        reopened.close();
    }

    @Test
    void aDirectoryThatIsAFileFailsToOpenWithAnIoException(@TempDir Path other) throws IOException {
        Path file = Files.createFile(other.resolve("not-a-directory"));

        assertThatThrownBy(() -> Stores.durable(file)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void aFailedOpeningReleasesTheDirectory() throws IOException {
        DirectoryStore store = durable.openWithReferenceModel();
        store.commit(newPerson("Bob"));
        DurableFixture.crash(store);
        Path segment = StoreFiles.lastSegment(directory);
        StoreFiles.writeInt(segment, 8, 9);

        assertThatThrownBy(durable::open).isInstanceOf(IllegalStateException.class);
        StoreFiles.writeInt(segment, 8, 1);

        assertThat(durable.open().latestBasis()).isEqualTo(new Basis(2));
    }

    @Test
    void aFlippedBitInTheLengthOfAMiddleRecordFailsTheOpeningInsteadOfDroppingTheRecordsAfterIt() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.rd1ToRd5(store, durable.clock);
        DurableFixture.crash(store);
        Path segment = StoreFiles.lastSegment(directory);
        List<Long> starts = StoreFiles.recordStarts(segment);
        long damaged = starts.get(starts.size() - 4);
        StoreFiles.damage(segment, damaged + 3);
        Map<String, byte[]> before = StoreFiles.contents(directory);

        assertThatThrownBy(durable::open).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(segment.toString()).hasMessageContaining("byte " + damaged);

        assertThat(StoreFiles.contents(directory)).containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
    }

    @Test
    void aTailOfZeroBytesAfterACrashIsTruncatedAndTheStoreGoesOn() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, durable.clock);
        StoreEquivalence.Observation before = StoreEquivalence.observe(store, told.ids(), EFFECTIVE_TIMES, List.of());
        DurableFixture.crash(store);
        Path segment = StoreFiles.lastSegment(directory);
        long end = StoreFiles.size(segment);
        StoreFiles.append(segment, new byte[4096]);

        DirectoryStore reopened = durable.open();

        assertThat(StoreFiles.size(segment)).isEqualTo(end);
        StoreEquivalence.assertEquivalent(before, StoreEquivalence.observe(reopened, told.ids(), EFFECTIVE_TIMES,
                List.of()));
        assertThat(DurableFixture.commit(reopened, newPerson("Bob")).basis()).isEqualTo(new Basis(10));
        reopened.close();
        assertThat(durable.open().latestBasis()).isEqualTo(new Basis(10));
    }

    @Test
    void rulesThatBreakTheNamingRulesAreRefusedBeforeTheDirectoryIsTouched(@TempDir Path other) {
        Path target = other.resolve("store");
        RuleDescriptor bad = new RuleDescriptor("Bad Name", 1, "d", "Organization", List.of());

        assertThatThrownBy(() -> Stores.durable(target, StoreSettings.builder().declaredRules(List.of(bad)).build()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(target).doesNotExist();
    }

    // ------------------------------------------------------------------ what recovery does not use

    /** A clock that must not be asked. */
    private static final InstantSource FORBIDDEN_CLOCK = () -> {
        throw new AssertionError("Recovery asked the clock.");
    };

    /** An ID generator that must not be asked for an ID. */
    private static final class ForbiddenGenerator implements IdGenerator {

        @Override
        public String next() {
            throw new AssertionError("Recovery asked the ID generator.");
        }

        @Override
        public boolean accepts(String text) {
            return true;
        }

        @Override
        public IdGenerator copy() {
            return this;
        }
    }

    /** A rule that counts its calls and finds every organization wrong. */
    private static final class CountingRule implements EntityRule {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public RuleDescriptor descriptor() {
            return LeiChecksumRule.DESCRIPTOR;
        }

        @Override
        public void check(EntityState version, RuleContext context) {
            calls.incrementAndGet();
            context.problem("invalid-check-digits", "lei", "Every LEI is wrong.");
        }
    }

    @Test
    void recoveryNeverAsksTheClockOrTheIdGeneratorOrRunsARuleAndDoesNotValidate() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.everyStory(store, durable.clock);
        StoreEquivalence.Observation before = StoreEquivalence.observe(store, told.ids(), EFFECTIVE_TIMES, List.of());
        store.writeSnapshot();
        durable.replay(store, ReferenceStories.rd9AcmeLei(ReferenceTales.entity(told.parties, ACME)));
        DurableFixture.crash(store);
        CountingRule strict = new CountingRule();

        DirectoryStore reopened = durable.open(FORBIDDEN_CLOCK, new ForbiddenGenerator(),
                List.of(strict, new TotalOwnershipRule()), List.of());

        assertThat(strict.calls).hasValue(0);
        assertThat(reopened.latestBasis()).isEqualTo(new Basis(before.latest().value() + 1));
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).isNotEmpty();
    }

    // ------------------------------------------------------------------ schema identifiers

    private static final Origin ITEMS_V1 = new Origin("items", "1.0.0");
    private static final Origin ITEMS_V2 = new Origin("items", "1.1.0");
    private static final Origin ITEMS_V3 = new Origin("items", "1.2.0");

    private static SchemaDefinition itemsV1() {
        return SchemaDefinition.builder()
                .trait("Labelled", trait -> trait.attribute("label", ValueType.STRING))
                .entityType("Thing", thing -> thing.includes("Labelled").attribute("size", ValueType.INT))
                .build();
    }

    /** Version 2 renames Thing to Item, keeps the old name as an alias, and adds a type, an attribute and a trait. */
    private static SchemaDefinition itemsV2() {
        return SchemaDefinition.builder()
                .trait("Labelled", trait -> trait.attribute("label", ValueType.STRING))
                .trait("Dated", trait -> trait.attribute("day", ValueType.DATE))
                .entityType("Item", item -> item.aliases("Thing").includes("Labelled", "Dated")
                        .attribute("size", ValueType.INT)
                        .attribute("weight", ValueType.DECIMAL))
                .entityType("Box", box -> box.attribute("volume", ValueType.LONG))
                .build();
    }

    private static SchemaDefinition itemsV3() {
        return SchemaDefinition.builder()
                .trait("Labelled", trait -> trait.attribute("label", ValueType.STRING))
                .trait("Dated", trait -> trait.attribute("day", ValueType.DATE))
                .entityType("Item", item -> item.aliases("Thing").includes("Labelled", "Dated")
                        .attribute("size", ValueType.INT)
                        .attribute("weight", ValueType.DECIMAL)
                        .attribute("colour", ValueType.STRING))
                .entityType("Box", box -> box.attribute("volume", ValueType.LONG))
                .entityType("Crate", crate -> crate.supertype("Box").attribute("slats", ValueType.INT))
                .relationshipType("Holds", relation -> relation.from("Box").to("Item").inverse("heldIn"))
                .build();
    }

    private static void register(Store store, Origin origin, SchemaDefinition definition) {
        assertThat(store.schema().register(origin, definition, Submission.by("agent:modeler")))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    @Test
    void schemaIdentifiersAreTheOnesTheRegistrationsAssignedAndTheNextRegistrationContinuesAfterThem() {
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).build());
        DirectoryStore store = durable.open();
        for (Store each : List.of(memory, store)) {
            register(each, ITEMS_V1, itemsV1());
            DurableFixture.commit(each, Transaction.by("tester").create("Thing", from(1, 1),
                    values("label", "first", "size", 3)).build());
            register(each, ITEMS_V2, itemsV2());
            DurableFixture.commit(each, Transaction.by("tester").create("Item", from(1, 1),
                    values("label", "second", "size", 4, "weight", new BigDecimal("2.50"))).build());
        }
        DurableFixture.crash(store);

        DirectoryStore reopened = durable.open();
        for (Store each : List.of(memory, reopened)) {
            register(each, ITEMS_V3, itemsV3());
            DurableFixture.commit(each, Transaction.by("tester").create("Crate", from(1, 1),
                    values("volume", 10L, "slats", 6)).build());
        }

        List<EntityId> ids = memory.view(Coordinates.effectiveAt(on(2, 1))).entities("Item").stream()
                .map(EntityState::id).toList();
        assertThat(ids).hasSize(2);
        List<Id> all = new ArrayList<>(ids);
        StoreEquivalence.assertEquivalent(StoreEquivalence.observe(memory, all, EFFECTIVE_TIMES, List.of()),
                StoreEquivalence.observe(reopened, all, EFFECTIVE_TIMES, List.of()));
        assertThat(reopened.schema().current().entityType("Thing").orElseThrow().id())
                .as("Thing was renamed to Item and kept its identifier")
                .isEqualTo(memory.schema().current().entityType("Item").orElseThrow().id());
        DurableFixture.crash(reopened);

        DirectoryStore again = durable.open();
        StoreEquivalence.assertEquivalent(StoreEquivalence.observe(memory, all, EFFECTIVE_TIMES, List.of()),
                StoreEquivalence.observe(again, all, EFFECTIVE_TIMES, List.of()));
        assertThat(again.schema().history()).hasSize(3);
        assertThat(again.schema().storedDefinition("items").orElseThrow().definition()).isEqualTo(itemsV3());
    }

    // ------------------------------------------------------------------ log segments and the two newest snapshot files

    @Test
    void oldSnapshotFilesAndLogSegmentsAreDeletedAndTheStoreRecoversFromWhatIsLeft() {
        durable.segmentLimit = 400;
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules()).build());
        DirectoryStore store = durable.open();
        DurableFixture.registerReferenceModel(memory);
        DurableFixture.registerReferenceModel(store);
        List<Path> everySegment = new ArrayList<>();
        List<Basis> snapshotBases = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 6; i++) {
                String name = "Person " + round + "." + i;
                DurableFixture.commit(memory, newPerson(name));
                DurableFixture.commit(store, newPerson(name));
            }
            snapshotBases.add(store.writeSnapshot());
            StoreFiles.segments(directory).stream().filter(segment -> !everySegment.contains(segment))
                    .forEach(everySegment::add);
        }

        List<Path> snapshots = StoreFiles.snapshots(directory);
        assertThat(snapshots).extracting(path -> path.getFileName().toString()).containsExactly(
                String.format("%020d.snapshot", snapshotBases.get(2).value()),
                String.format("%020d.snapshot", snapshotBases.get(3).value()));
        List<Path> segments = StoreFiles.segments(directory);
        assertThat(everySegment).as("the log had many segments").hasSizeGreaterThan(5);
        assertThat(segments).as("segments with records of the older snapshot only were deleted")
                .hasSizeLessThan(everySegment.size());
        long firstKept = Long.parseLong(segments.getFirst().getFileName().toString().replace(".log", ""));
        assertThat(firstKept).as("the log starts at or before the record after the older snapshot")
                .isLessThanOrEqualTo(snapshotBases.get(2).value() + 1);
        assertThat(firstKept).as("and not at the start").isGreaterThan(1);

        DurableFixture.crash(store);
        DirectoryStore reopened = durable.open();
        List<Id> ids = new ArrayList<>(memory.view(Coordinates.effectiveAt(on(2, 1)))
                .entities("Person").stream().map(EntityState::id).toList());
        assertThat(ids).hasSize(24);
        StoreEquivalence.assertEquivalent(StoreEquivalence.observe(memory, ids, EFFECTIVE_TIMES, List.of()),
                StoreEquivalence.observe(reopened, ids, EFFECTIVE_TIMES, List.of()));
    }

    @Test
    void ifTheNewestSnapshotFileIsDamagedTheOlderOneAndTheLogAfterItRecoverEverythingAfterPruning() {
        durable.segmentLimit = 400;
        DirectoryStore store = durable.openWithReferenceModel();
        List<Transaction> all = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 6; i++) {
                Transaction person = newPerson("Person " + round + "." + i);
                DurableFixture.commit(store, person);
                all.add(person);
            }
            store.writeSnapshot();
        }
        DurableFixture.crash(store);
        Path newest = StoreFiles.snapshots(directory).getLast();
        StoreFiles.damage(newest, StoreFiles.size(newest) - 10);

        DirectoryStore reopened = durable.open();

        assertThat(reopened.transactions()).hasSize(all.size());
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Person")).hasSize(all.size());
    }

    @Test
    void aSnapshotFileAloneRecoversTheStoreIncludingIdempotencyKeys() {
        DirectoryStore store = durable.openWithReferenceModel();
        ReferenceTales.Told told = ReferenceTales.rd1ToRd5(store, durable.clock);
        Transaction keyed = Transaction.by("importer").idempotencyKey("once").create("Person", from(1, 1),
                values("name", "Keyed")).build();
        Receipt receipt = DurableFixture.commit(store, keyed);
        List<Id> ids = new ArrayList<>(told.ids());
        ids.addAll(receipt.created());
        StoreEquivalence.Observation before = StoreEquivalence.observe(store, ids, EFFECTIVE_TIMES, List.of(keyed));
        store.close();
        durable.open().close();
        for (Path segment : StoreFiles.segments(directory).subList(0, StoreFiles.segments(directory).size() - 1)) {
            try {
                Files.delete(segment);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        DirectoryStore reopened = durable.open();

        assertThat(StoreFiles.segments(directory)).as("only the empty segment is left").hasSize(1);
        StoreEquivalence.assertEquivalent(before, StoreEquivalence.observe(reopened, ids, EFFECTIVE_TIMES,
                List.of(keyed)));
        assertThat(reopened.commit(keyed)).isEqualTo(new CommitResult.Committed(receipt));
        assertThat(reopened.dryRun(keyed)).isInstanceOf(DryRunResult.WouldCommit.class);
    }

    // ------------------------------------------------------------------ a commit that cannot be written

    @Test
    void afterAFailedWriteRegistrationsAreRefusedTooAndReadsDryRunsAndSnapshotsGoOn() {
        DirectoryStore store = durable.openWithReferenceModel();
        Receipt parties = durable.replay(store, ReferenceStories.rd1Parties());
        store.breakLog();
        Transaction person = newPerson("Bob");

        assertThatThrownBy(() -> store.commit(person)).isInstanceOf(UncheckedIOException.class);

        assertThatThrownBy(() -> store.schema().register(new Origin("more", "1"), SchemaDefinition.builder()
                .entityType("More").build())).isInstanceOf(IllegalStateException.class);
        assertThat(store.schema().current().entityType("More")).isEmpty();
        assertThat(store.dryRun(person)).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).hasSize(2);
        assertThat(store.writeSnapshot()).isEqualTo(store.latestBasis());
        store.close();

        DirectoryStore reopened = durable.open();
        assertThat(reopened.latestBasis()).isEqualTo(new Basis(2));
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Party")).hasSize(parties.created().size());
        assertThat(DurableFixture.commit(reopened, person).basis()).isEqualTo(new Basis(3));
    }

    @Test
    void aSchemaRegistrationThatCannotBeWrittenIsNotApplied() {
        DirectoryStore store = durable.open();
        store.breakLog();

        assertThatThrownBy(() -> store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(store.schema().current().isEmpty()).isTrue();
        assertThat(store.schema().history()).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(Basis.NONE);
        assertThatThrownBy(() -> store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ commits while a snapshot file is written

    @Test
    void commitsGoOnWhileSnapshotFilesAreWrittenAndEverythingIsRecovered() throws InterruptedException {
        durable.segmentLimit = 4_000;
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).rules(ReferenceModel.executableRules()).build());
        DirectoryStore store = durable.open();
        DurableFixture.registerReferenceModel(memory);
        DurableFixture.registerReferenceModel(store);
        int commits = 300;
        AtomicBoolean done = new AtomicBoolean();
        List<Throwable> failures = new ArrayList<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < commits; i++) {
                    DurableFixture.commit(store, newPerson("Person " + i));
                }
            } catch (Throwable e) {
                failures.add(e);
            } finally {
                done.set(true);
            }
        });
        writer.start();
        int snapshots = 0;
        do {
            store.writeSnapshot();
            snapshots++;
        } while (!done.get());
        writer.join();
        for (int i = 0; i < commits; i++) {
            DurableFixture.commit(memory, newPerson("Person " + i));
        }
        List<Id> ids = new ArrayList<>(memory.view(Coordinates.effectiveAt(on(2, 1)))
                .entities("Person").stream().map(EntityState::id).toList());
        StoreEquivalence.Observation expected = StoreEquivalence.observeSampled(memory, ids, List.of(on(2, 1)),
                List.of());
        assertThat(failures).isEmpty();
        assertThat(snapshots).isPositive();
        DurableFixture.crash(store);

        DirectoryStore reopened = durable.open();

        assertThat(ids).hasSize(commits);
        assertThat(reopened.latestBasis()).isEqualTo(new Basis(commits + 1L));
        StoreEquivalence.assertEquivalent(expected, StoreEquivalence.observeSampled(reopened, ids,
                List.of(on(2, 1)), List.of()));
    }

    // ------------------------------------------------------------------ text that UTF-8 cannot hold

    @Test
    void textWithUnpairedSurrogatesSurvivesCommitReopeningAndSnapshotsAndEqualsTheInMemoryTwin() {
        String high = "\uD800";
        String low = "\uDC00";
        String body = "a" + high + "b" + low + "c" + high;
        Origin origin = new Origin("notes", "1");
        SchemaDefinition notes = SchemaDefinition.builder().entityType("Note", note -> note
                .description("Notes with " + high + " in " + low)
                .attribute("body", ValueType.STRING, text -> text.description("the " + low + " body")
                        .examples(Value.of(high + "x"))))
                .build();
        Submission submission = new Submission("modeler" + high + "x" + low, Optional.of("hr" + low),
                Optional.of("why " + high + " and " + low));
        Transaction transaction = Transaction.by("agent:" + high + "x" + low).onBehalfOf("sales" + low)
                .reason("because " + high + " then " + low).idempotencyKey("key" + high)
                .create("Note", from(1, 1), values("body", body)).build();
        TestClock memoryClock = new TestClock();
        Store memory = Stores.inMemory(StoreSettings.builder().clock(memoryClock)
                .idGenerator(IdGenerators.deterministic()).build());
        DirectoryStore store = durable.open(durable.clock, IdGenerators.deterministic(), List.of(), List.of());
        for (Store each : List.of(memory, store)) {
            assertThat(each.schema().register(origin, notes, submission)).isInstanceOf(RegistrationResult.Applied.class);
        }
        Receipt expected = DurableFixture.commit(memory, transaction);
        assertThat(DurableFixture.commit(store, transaction)).isEqualTo(expected);
        List<Id> ids = new ArrayList<>(expected.created());
        List<Transaction> keyed = List.of(transaction);
        StoreEquivalence.Observation twin = StoreEquivalence.observe(memory, ids, EFFECTIVE_TIMES, keyed);
        StoreEquivalence.assertEquivalent(twin, StoreEquivalence.observe(store, ids, EFFECTIVE_TIMES, keyed));

        // Recovered from the log alone.
        DurableFixture.crash(store);
        assertThat(contains(StoreFiles.lastSegment(directory), (byte) 0xED, (byte) 0xA0, (byte) 0x80))
                .as("the lone high surrogate is in the log as its own three bytes").isTrue();
        assertThat(contains(StoreFiles.lastSegment(directory), (byte) 0xED, (byte) 0xB0, (byte) 0x80))
                .as("the lone low surrogate too").isTrue();
        DirectoryStore fromLog = durable.open(durable.clock, IdGenerators.deterministic(), List.of(), List.of());
        StoreEquivalence.assertEquivalent(twin, StoreEquivalence.observe(fromLog, ids, EFFECTIVE_TIMES, keyed));
        assertRecovered(fromLog, expected, body, transaction, submission, notes);

        // Recovered from a snapshot file alone.
        fromLog.close();
        durable.open(durable.clock, IdGenerators.deterministic(), List.of(), List.of()).close();
        for (Path segment : StoreFiles.segments(directory).subList(0, StoreFiles.segments(directory).size() - 1)) {
            try {
                Files.delete(segment);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(contains(StoreFiles.snapshots(directory).getLast(), (byte) 0xED, (byte) 0xA0, (byte) 0x80))
                .as("and in the snapshot file").isTrue();
        DirectoryStore fromSnapshot = durable.open(durable.clock, IdGenerators.deterministic(), List.of(), List.of());
        assertThat(StoreFiles.segments(directory)).hasSize(1);
        StoreEquivalence.assertEquivalent(twin, StoreEquivalence.observe(fromSnapshot, ids, EFFECTIVE_TIMES, keyed));
        assertRecovered(fromSnapshot, expected, body, transaction, submission, notes);
    }

    /** Tells whether a file holds a sequence of bytes. */
    private static boolean contains(Path file, byte... sequence) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i + sequence.length <= bytes.length; i++) {
                if (Arrays.equals(bytes, i, i + sequence.length, sequence, 0, sequence.length)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertRecovered(Store store, Receipt receipt, String body, Transaction transaction,
            Submission submission, SchemaDefinition notes) {
        EntityId note = (EntityId) receipt.created().getFirst();
        assertThat(store.view(Coordinates.effectiveAt(on(2, 1))).entity(note)).get().satisfies(state ->
                assertThat(state.values().get("body")).isEqualTo(Value.of(body)));
        assertThat(store.transactions()).singleElement().satisfies(record -> {
            assertThat(record.principal()).isEqualTo(transaction.principal());
            assertThat(record.onBehalfOf()).isEqualTo(transaction.onBehalfOf());
            assertThat(record.reason()).isEqualTo(transaction.reason());
        });
        assertThat(store.schema().history()).singleElement().satisfies(registration -> {
            assertThat(registration.principal()).isEqualTo(submission.principal());
            assertThat(registration.onBehalfOf()).isEqualTo(submission.onBehalfOf());
            assertThat(registration.reason()).isEqualTo(submission.reason());
        });
        assertThat(store.schema().storedDefinition("notes").orElseThrow().definition()).isEqualTo(notes);
        assertThat(store.commit(transaction)).isEqualTo(new CommitResult.Committed(receipt));
    }

    // ------------------------------------------------------------------ IDs

    /** A generator that hands out an ID twice, as one that starts again after a restart does. */
    private static IdGenerator repeating(List<String> sequence) {
        return new IdGenerator() {
            private int next;

            @Override
            public String next() {
                return sequence.get(next++);
            }

            @Override
            public boolean accepts(String text) {
                return true;
            }

            @Override
            public IdGenerator copy() {
                IdGenerator source = this;
                int position = next;
                return new IdGenerator() {
                    private int next = position;

                    @Override
                    public String next() {
                        return sequence.get(next++);
                    }

                    @Override
                    public boolean accepts(String text) {
                        return source.accepts(text);
                    }

                    @Override
                    public IdGenerator copy() {
                        return repeating(sequence);
                    }
                };
            }
        };
    }

    @Test
    void anInMemoryStoreAlsoSkipsAGeneratedIdThatItAlreadyHas() {
        TestClock clock = new TestClock();
        Store store = Stores.inMemory(StoreSettings.builder().clock(clock)
                .idGenerator(repeating(List.of("a", "a", "a", "b", "c"))).build());
        store.schema().register(new Origin("things", "1"), SchemaDefinition.builder().entityType("Thing").build());

        Receipt first = DurableFixture.commit(store, Transaction.by("tester").create("Thing", from(1, 1), values())
                .build());
        Receipt second = DurableFixture.commit(store, Transaction.by("tester").create("Thing", from(1, 1), values())
                .build());

        assertThat(first.created()).extracting(id -> id.value()).containsExactly("a");
        assertThat(second.created()).extracting(id -> id.value()).as("a is skipped").containsExactly("b");
    }

    @Test
    void aDurableStoreThatOpensWithAGeneratorThatStartsAgainNeverRepeatsAnID() {
        DirectoryStore first = durable.openWithReferenceModel();
        Receipt parties = durable.replay(first, ReferenceStories.rd1Parties());
        DurableFixture.crash(first);

        DirectoryStore second = durable.open();
        Receipt more = DurableFixture.commit(second, Transaction.by("tester").create("Person", from(1, 1),
                values("name", "Bob")).create("Person", from(1, 1), values("name", "Carol")).build());

        assertThat(more.created()).doesNotContainAnyElementsOf(parties.created()).doesNotHaveDuplicates();
        assertThat(more.created()).extracting(id -> id.value()).containsExactly(
                String.format("00000000-0000-7000-8000-%012x", 4), String.format("00000000-0000-7000-8000-%012x", 5));
    }
}
