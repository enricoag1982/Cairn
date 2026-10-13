package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.names;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.schema.Multiplicity.EXACTLY_ONE;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ONE_OR_MORE;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ZERO_OR_MORE;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.IdGenerator;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.PurgeRecord;
import io.github.enricoag1982.cairn.data.PurgeResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceQueries;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A purge beyond the scenarios of the {@code purge} spec (design D1, D2 and D3 of {@code add-purge}): what it checks,
 * which entities and relationships it removes, what it keeps of IDs and idempotency keys, what it records, and what
 * views read afterwards.
 */
class PurgeTest {

    private static final TemporaryId FOLDER = new TemporaryId("folder");
    private static final TemporaryId SPREADSHEET = new TemporaryId("spreadsheet");
    private static final TemporaryId NOTES = new TemporaryId("notes");
    private static final TemporaryId CHUNK_1 = new TemporaryId("chunk1");
    private static final TemporaryId CHUNK_2 = new TemporaryId("chunk2");
    private static final TemporaryId URGENT = new TemporaryId("urgent");
    private static final TemporaryId CONTAINS_1 = new TemporaryId("contains1");
    private static final TemporaryId CONTAINS_2 = new TemporaryId("contains2");
    private static final TemporaryId SPLITS_1 = new TemporaryId("splits1");
    private static final TemporaryId SPLITS_2 = new TemporaryId("splits2");
    private static final TemporaryId TAGGED = new TemporaryId("tagged");
    private static final TemporaryId HOME = new TemporaryId("home");
    private static final TemporaryId HAS_ADDRESS = new TemporaryId("hasAddress");

    /** A schema of folders, files and chunks, with compositions in two steps, and of teams, which need a member. */
    private static final SchemaDefinition TREE = SchemaDefinition.builder()
            .entityType("Folder", e -> e.attribute("name", STRING, a -> a.required()))
            .entityType("File", e -> e.attribute("name", STRING, a -> a.required()))
            .entityType("Chunk", e -> e.attribute("name", STRING, a -> a.required()))
            .entityType("Tag", e -> e.attribute("name", STRING, a -> a.required()))
            .entityType("Team", e -> e.attribute("name", STRING, a -> a.required()))
            .entityType("Member", e -> e.attribute("name", STRING, a -> a.required()))
            .relationshipType("Contains", r -> r.from("Folder").to("File").composition()
                    .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(EXACTLY_ONE).inverse("folder"))
            .relationshipType("Splits", r -> r.from("File").to("Chunk").composition()
                    .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(EXACTLY_ONE).inverse("file"))
            .relationshipType("Tagged", r -> r.from("File").to("Tag")
                    .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE).inverse("tagging"))
            .relationshipType("MemberOf", r -> r.from("Member").to("Team")
                    .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ONE_OR_MORE).inverse("members"))
            .build();

    private final ReferenceStore reference = new ReferenceStore();

    private static Purge purge(EntityId... entities) {
        Purge.Builder builder = Purge.by("dpo").reason("Erasure request");
        for (EntityId entity : entities) {
            builder.entity(entity);
        }
        return builder.build();
    }

    private static PurgeResult.Purged purged(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Purged.class);
        return (PurgeResult.Purged) result;
    }

    private static List<Problem> rejected(PurgeResult result) {
        assertThat(result).as("purge result").isInstanceOf(PurgeResult.Rejected.class);
        return ((PurgeResult.Rejected) result).problems();
    }

    private PurgeResult.Purged purgeNow(EntityId... entities) {
        return purged(reference.store.purge(purge(entities)));
    }

    private static Transaction keyed(String key, Transaction transaction) {
        return new Transaction(transaction.principal(), transaction.onBehalfOf(), transaction.reason(),
                Optional.of(key), transaction.writes());
    }

    private static Receipt commitReceipt(CommitResult result) {
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return ((CommitResult.Committed) result).receipt();
    }

    private static List<Problem> problemsOf(CommitResult result) {
        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        return ((CommitResult.Rejected) result).problems();
    }

    // ------------------------------------------------------------------ checking

    @Test
    void everyUnknownIdIsReportedAtItsPositionAndNothingChanges() {
        EntityId alice = entity(reference.createParties(), ALICE);
        Basis before = reference.store.latestBasis();
        EntityId unknown = ReferenceStore.id(99);
        EntityId other = ReferenceStore.id(98);

        List<Problem> problems = rejected(reference.store.purge(purge(alice, unknown, other)));

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_ENTITY,
                ProblemCode.UNKNOWN_ENTITY);
        assertThat(problems).extracting(Problem::path).containsExactly("@purge.1", "@purge.2");
        assertThat(problems.getFirst().message()).contains(unknown.value());
        assertThat(problems.getFirst().found()).contains(unknown.value());
        assertThat(reference.view(on(3, 31)).entity(alice)).as("Alice is not purged").isPresent();
        assertThat(reference.store.latestBasis()).as("no basis is used").isEqualTo(before);
        assertThat(reference.store.purges()).isEmpty();
        assertThat(reference.store.transactions()).hasSize(1);
    }

    @Test
    void theIdOfARelationshipIsNotTheIdOfAnEntityAndAPurgedEntityIsUnknown() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(reference.replay(ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        purgeNow(alice);

        List<Problem> asRelationship = rejected(reference.store.purge(purge(new EntityId(employs.value()))));
        List<Problem> again = rejected(reference.store.purge(purge(alice)));

        assertThat(asRelationship).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ENTITY);
            assertThat(problem.path()).isEqualTo("@purge.0");
        });
        assertThat(again).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ENTITY);
            assertThat(problem.message()).contains("purged earlier");
        });
    }

    @Test
    void everyProblemOfAPurgeIsReportedTheUnknownOnesFirst() {
        Receipt accounts = reference.createAccounts();
        EntityId beta = entity(reference.parties, BETA);
        EntityId acme = entity(reference.parties, ACME);
        EntityId a1 = entity(accounts, A1);
        EntityId a2 = entity(accounts, ReferenceStories.A2);

        List<Problem> problems = rejected(reference.store.purge(purge(beta, ReferenceStore.id(99), acme)));

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_ENTITY,
                ProblemCode.TOO_FEW_RELATIONSHIPS, ProblemCode.TOO_FEW_RELATIONSHIPS);
        assertThat(problems.get(0).path()).isEqualTo("@purge.1");
        assertThat(problems.get(1).path()).as("the accounts, in the order of their IDs").isEqualTo(a1.value());
        assertThat(problems.get(2).path()).isEqualTo(a2.value());
    }

    @Test
    void aMinimumMustHoldAtEveryPointInEffectiveTime() {
        Receipt accounts = reference.createAccounts();
        EntityId acme = entity(reference.parties, ACME);
        EntityId beta = entity(reference.parties, BETA);
        EntityId a1 = entity(accounts, A1);
        // From 1 June A-1 is held by ACME, so without Beta it has no holder from 1 January until 1 June.
        reference.commit(Transaction.by("importer")
                .endDate(relationship(accounts, ReferenceStories.HELD_BY_A1), on(6, 1))
                .relate("HeldBy", a1, acme, from(6, 1), values()).build());

        List<Problem> problems = rejected(reference.store.purge(purge(beta)));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
            assertThat(problem.path()).isEqualTo(a1.value());
            assertThat(problem.message()).contains("2026-01-01T00:00:00Z").contains("2026-06-01T00:00:00Z");
        });
        assertThat(reference.view(on(3, 31)).entity(beta)).isPresent();
    }

    @Test
    void aMinimumAtTheTargetEndIsChecked() {
        ReferenceStore tree = ReferenceStore.with(TREE);
        Receipt receipt = tree.commit(Transaction.by("importer")
                .create(new TemporaryId("team"), "Team", from(1, 1), values("name", "Core"))
                .create(new TemporaryId("ann"), "Member", from(1, 1), values("name", "Ann"))
                .create(new TemporaryId("bob"), "Member", from(1, 1), values("name", "Bob"))
                .relate("MemberOf", new TemporaryId("ann"), new TemporaryId("team"), from(1, 1), values())
                .relate("MemberOf", new TemporaryId("bob"), new TemporaryId("team"), from(1, 1), values())
                .build());
        EntityId team = entity(receipt, new TemporaryId("team"));
        EntityId ann = entity(receipt, new TemporaryId("ann"));
        EntityId bob = entity(receipt, new TemporaryId("bob"));

        PurgeResult.Purged first = purged(tree.store.purge(purge(ann)));
        List<Problem> problems = rejected(tree.store.purge(purge(bob)));
        PurgeResult.Purged both = purged(tree.store.purge(purge(bob, team)));

        assertThat(first.entities()).containsExactly(ann);
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
            assertThat(problem.path()).isEqualTo(team.value());
            assertThat(problem.message()).contains("members");
            assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Purge " + team.value()));
        });
        assertThat(both.entities()).containsExactly(bob, team);
        assertThat(tree.view(on(3, 31)).entity(team)).isEmpty();
    }

    @Test
    void anOptionalRelationshipDoesNotStopAPurge() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        RelationshipId owns = relationship(reference.replay(ReferenceStories.rd2Ownership(acme, beta)),
                ACME_OWNS_BETA);

        PurgeResult.Purged result = purgeNow(beta);

        assertThat(result.entities()).containsExactly(beta);
        assertThat(result.relationships()).containsExactly(owns);
        assertThat(reference.view(on(3, 31)).entity(acme)).as("ACME stays").isPresent();
        assertThat(reference.view(on(3, 31)).navigate(acme, "Owns")).isEmpty();
    }

    @Test
    void aPurgeWhoseEntitiesAreNamedTwiceRemovesThemOnce() {
        EntityId alice = entity(reference.createParties(), ALICE);

        PurgeResult.Purged result = purgeNow(alice, alice);

        assertThat(result.entities()).containsExactly(alice);
    }

    @Test
    void rulesDoNotRunAtAPurge() {
        AtomicInteger runs = new AtomicInteger();
        TestEntityRule rule = TestEntityRule.of("counter", "Person", (version, context) -> runs.incrementAndGet());
        ReferenceStore counted = ReferenceStore.withRules(
                TestEntityRule.namedIn(ReferenceModel.definition(), "Person",
                        rule.descriptor()),
                ReferenceStore.rulesWith(rule), ReferenceModel.declaredRules());
        EntityId alice = entity(counted.createParties(), ALICE);
        int before = runs.get();
        assertThat(before).isPositive();

        purged(counted.store.purge(purge(alice)));

        assertThat(runs.get()).as("no rule ran").isEqualTo(before);
    }

    @Test
    void aRuleCannotPurgeWhileTheStoreRunsRules() {
        AtomicReference<Store> self = new AtomicReference<>();
        AtomicReference<EntityId> target = new AtomicReference<>();
        TestEntityRule rule = TestEntityRule.of("purger", "Person", (version, context) ->
                assertThatThrownBy(() -> self.get().purge(purge(target.get())))
                        .isInstanceOf(IllegalStateException.class));
        ReferenceStore store = ReferenceStore.withRules(
                TestEntityRule.namedIn(ReferenceModel.definition(), "Person",
                        rule.descriptor()),
                ReferenceStore.rulesWith(rule), ReferenceModel.declaredRules());
        self.set(store.store);
        target.set(ReferenceStore.id(1));

        assertThat(store.reject(ReferenceStories.rd1Parties().transaction())).hasSize(1)
                .allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED));
        assertThat(store.store.purges()).isEmpty();
    }

    // ------------------------------------------------------------------ what a purge removes

    private record Tree(ReferenceStore store, EntityId folder, EntityId spreadsheet, EntityId notes, EntityId chunk1,
            EntityId chunk2, EntityId urgent, RelationshipId contains1, RelationshipId contains2,
            RelationshipId splits1, RelationshipId splits2, RelationshipId tagged) {
    }

    /** A folder with two files, a spreadsheet with two chunks and notes without, and an urgent tag on the notes. */
    private static Tree aFolder() {
        ReferenceStore tree = ReferenceStore.with(TREE);
        Receipt receipt = tree.commit(Transaction.by("importer")
                .create(FOLDER, "Folder", from(1, 1), values("name", "Work"))
                .create(SPREADSHEET, "File", from(1, 1), values("name", "budget.xlsx"))
                .create(CHUNK_1, "Chunk", from(1, 1), values("name", "budget part 1"))
                .create(CHUNK_2, "Chunk", from(1, 1), values("name", "budget part 2"))
                .create(NOTES, "File", from(1, 1), values("name", "notes.txt"))
                .create(URGENT, "Tag", from(1, 1), values("name", "urgent"))
                .relate(CONTAINS_1, "Contains", FOLDER, SPREADSHEET, from(1, 1), values())
                .relate(SPLITS_1, "Splits", SPREADSHEET, CHUNK_1, from(1, 1), values())
                .relate(SPLITS_2, "Splits", SPREADSHEET, CHUNK_2, from(1, 1), values())
                .relate(CONTAINS_2, "Contains", FOLDER, NOTES, from(1, 1), values())
                .relate(TAGGED, "Tagged", NOTES, URGENT, from(1, 1), values())
                .build());
        return new Tree(tree, entity(receipt, FOLDER), entity(receipt, SPREADSHEET), entity(receipt, NOTES),
                entity(receipt, CHUNK_1), entity(receipt, CHUNK_2), entity(receipt, URGENT),
                relationship(receipt, CONTAINS_1), relationship(receipt, CONTAINS_2), relationship(receipt, SPLITS_1),
                relationship(receipt, SPLITS_2), relationship(receipt, TAGGED));
    }

    @Test
    void theTargetsOfCompositionsAreRemovedTransitivelyDepthFirstWithTheirRelationships() {
        Tree tree = aFolder();

        PurgeResult.Purged result = purged(tree.store().store.purge(purge(tree.folder())));

        assertThat(result.entities()).as("the folder, then each file with its chunks")
                .containsExactly(tree.folder(), tree.spreadsheet(), tree.chunk1(), tree.chunk2(), tree.notes());
        assertThat(result.relationships()).as("in the order of their IDs")
                .containsExactly(tree.contains1(), tree.splits1(), tree.splits2(), tree.contains2(), tree.tagged());
        View view = tree.store().view(on(3, 31));
        assertThat(view.entities("Folder")).isEmpty();
        assertThat(view.entities("File")).isEmpty();
        assertThat(view.entities("Chunk")).isEmpty();
        assertThat(view.entities("Tag")).extracting(EntityState::id).as("a tag is not composed").containsExactly(
                tree.urgent());
        assertThat(view.navigate(tree.urgent(), "tagging")).as("its relationship to the notes is gone").isEmpty();
    }

    @Test
    void aTargetThatIsNamedTooIsRemovedOnce() {
        Tree tree = aFolder();

        PurgeResult.Purged result = purged(tree.store().store.purge(purge(tree.chunk2(), tree.folder())));

        assertThat(result.entities()).containsExactly(tree.chunk2(), tree.folder(), tree.spreadsheet(),
                tree.chunk1(), tree.notes());
    }

    @Test
    void aTargetCanBePurgedWithoutItsSource() {
        Tree tree = aFolder();

        PurgeResult.Purged result = purged(tree.store().store.purge(purge(tree.chunk1())));

        assertThat(result.entities()).containsExactly(tree.chunk1());
        assertThat(result.relationships()).containsExactly(tree.splits1());
        assertThat(tree.store().view(on(3, 31)).entity(tree.spreadsheet())).isPresent();
        assertThat(tree.store().view(on(3, 31)).navigate(tree.spreadsheet(), "Splits")).hasSize(1);
    }

    @Test
    void aTargetThatOnlyThePurgedEntityEverComposedGoesWithItEvenIfTheCompositionEndedLongAgo() {
        Receipt parties = reference.createParties();
        EntityId alice = entity(parties, ALICE);
        EffectivePeriod untilMarch = EffectivePeriod.between(on(1, 1), on(3, 1));
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", untilMarch,
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", alice, HOME, untilMarch, values()).build());
        EntityId home = entity(withAddress, HOME);
        assertThat(reference.view(on(6, 1)).navigate(alice, "HasAddress")).as("it ended on 1 March").isEmpty();

        PurgeResult.Purged result = purgeNow(alice);

        assertThat(result.entities()).containsExactly(alice, home);
        assertThat(reference.view(on(1, 31)).entity(home)).isEmpty();
    }

    /** A folder and the file that it holds until 1 March and another folder holds from then on. */
    private record Handover(ReferenceStore store, EntityId first, EntityId second, EntityId file,
            RelationshipId firstContains, RelationshipId secondContains) {
    }

    private static Handover aFileThatChangedFolders() {
        ReferenceStore tree = ReferenceStore.with(TREE);
        Receipt receipt = tree.commit(Transaction.by("importer")
                .create(FOLDER, "Folder", from(1, 1), values("name", "Work"))
                .create(NOTES, "Folder", from(1, 1), values("name", "Archive"))
                .create(SPREADSHEET, "File", from(1, 1), values("name", "budget.xlsx"))
                .relate(CONTAINS_1, "Contains", FOLDER, SPREADSHEET, from(1, 1), values()).build());
        RelationshipId firstContains = relationship(receipt, CONTAINS_1);
        Receipt moved = tree.commit(Transaction.by("importer")
                .endDate(firstContains, on(3, 1))
                .relate(CONTAINS_2, "Contains", entity(receipt, NOTES), entity(receipt, SPREADSHEET), from(3, 1),
                        values()).build());
        return new Handover(tree, entity(receipt, FOLDER), entity(receipt, NOTES), entity(receipt, SPREADSHEET),
                firstContains, relationship(moved, CONTAINS_2));
    }

    @Test
    void aTargetThatAnotherEntityComposedTooStaysAndEachOfTheirPurgesIsRefusedForIt() {
        Handover handover = aFileThatChangedFolders();

        for (EntityId folder : List.of(handover.first(), handover.second())) {
            List<Problem> problems = rejected(handover.store().store.purge(purge(folder)));

            assertThat(problems).as("the folder " + folder).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
                assertThat(problem.path()).isEqualTo(handover.file().value());
            });
        }
        assertThat(handover.store().store.purges()).isEmpty();
        assertThat(handover.store().view(on(2, 1)).entity(handover.file())).isPresent();
    }

    @Test
    void aTargetThatAnotherEntityComposedTooGoesOnceEveryEntityThatComposedItIsPurged() {
        Handover handover = aFileThatChangedFolders();

        PurgeResult.Purged both = purged(handover.store().store.purge(purge(handover.first(), handover.second())));

        assertThat(both.entities()).as("the folders, then the file that only they composed")
                .containsExactly(handover.first(), handover.second(), handover.file());
        assertThat(both.relationships()).containsExactly(handover.firstContains(), handover.secondContains());
        assertThat(handover.store().view(on(2, 1)).entity(handover.file())).isEmpty();
    }

    @Test
    void aTargetThatIsNamedIsPurgedWithoutItsSourcesAndTheOtherSourceRemains() {
        Handover handover = aFileThatChangedFolders();

        PurgeResult.Purged result = purged(handover.store().store.purge(purge(handover.first(), handover.file())));

        assertThat(result.entities()).containsExactly(handover.first(), handover.file());
        assertThat(result.relationships()).containsExactly(handover.firstContains(), handover.secondContains());
        assertThat(handover.store().view(on(4, 1)).entity(handover.second())).as("the other folder stays").isPresent();
    }

    @Test
    void aTargetWaitsForItsOtherSourcesThatThePurgeTakesToo() {
        ReferenceStore tree = ReferenceStore.with(TREE);
        Receipt receipt = tree.commit(Transaction.by("importer")
                .create(FOLDER, "Folder", from(1, 1), values("name", "Work"))
                .create(SPREADSHEET, "File", from(1, 1), values("name", "budget.xlsx"))
                .create(NOTES, "File", from(1, 1), values("name", "notes.txt"))
                .create(CHUNK_1, "Chunk", from(1, 1), values("name", "shared"))
                .relate(CONTAINS_1, "Contains", FOLDER, SPREADSHEET, from(1, 1), values())
                .relate(CONTAINS_2, "Contains", FOLDER, NOTES, from(1, 1), values())
                .relate(SPLITS_1, "Splits", SPREADSHEET, CHUNK_1, from(1, 1), values()).build());
        EntityId chunk = entity(receipt, CHUNK_1);
        tree.commit(Transaction.by("importer").endDate(relationship(receipt, SPLITS_1), on(3, 1))
                .relate("Splits", entity(receipt, NOTES), chunk, from(3, 1), values()).build());

        PurgeResult.Purged result = purged(tree.store.purge(purge(entity(receipt, SPREADSHEET),
                entity(receipt, FOLDER))));

        assertThat(result.entities()).as("the chunk waits for the notes, which the folder takes").containsExactly(
                entity(receipt, SPREADSHEET), entity(receipt, FOLDER), entity(receipt, NOTES), chunk);
    }

    @Test
    void theRelationshipsOfThePurgedEntitiesIncludeThoseToOtherPurgedEntitiesOnce() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        RelationshipId owns = relationship(reference.replay(ReferenceStories.rd2Ownership(acme, beta)),
                ACME_OWNS_BETA);

        PurgeResult.Purged result = purgeNow(acme, beta);

        assertThat(result.relationships()).containsExactly(owns);
    }

    @Test
    void aViewThatWasOpenedBeforeAPurgeReadsThePurgedStateAfterIt() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(reference.replay(ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        View opened = reference.view(on(3, 31));
        View atBasis = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(new Basis(3)));
        assertThat(opened.entity(alice)).isPresent();
        assertThat(opened.navigate(beta, "Employs")).hasSize(1);
        assertThat(names(rows(opened.query(ReferenceQueries.parties())), "p")).contains("Alice");

        purgeNow(alice);

        for (View view : List.of(opened, atBasis)) {
            assertThat(view.entity(alice)).isEmpty();
            assertThat(view.timeline(alice)).isEmpty();
            assertThat(view.relationship(employs)).isEmpty();
            assertThat(view.relationshipTimeline(employs)).isEmpty();
            assertThat(view.entities("Person")).isEmpty();
            assertThat(view.navigate(beta, "Employs")).isEmpty();
            assertThat(view.navigateTimeline(beta, "Employs")).isEmpty();
            assertThat(names(rows(view.query(ReferenceQueries.parties())), "p")).containsExactly("ACME", "Beta");
        }
        assertThat(opened.basis()).as("a view keeps its basis").isEqualTo(new Basis(3));
    }

    @Test
    void aValueThatMustBeUniqueIsFreeAgainOnceItsEntityIsPurged() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        assertThat(reference.rejectReplay(ReferenceStories.rd9Gamma())).as("while ACME has the LEI").hasSize(1)
                .allSatisfy(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.UNIQUE_VALUE_TAKEN));

        purgeNow(acme);
        Receipt gamma = reference.replay(ReferenceStories.rd9Gamma());

        assertThat(gamma.created()).hasSize(1);
        assertThat(reference.view(on(3, 31)).entities("Organization")).extracting(EntityState::entityType)
                .containsExactly("Organization", "Organization");
    }

    // ------------------------------------------------------------------ IDs

    /** A generator that returns the given IDs in turn, and again from the start after the last. */
    private static final class CyclingState implements IdGenerator {
        private final List<String> ids;
        private int position;

        CyclingState(List<String> ids, int position) {
            this.ids = ids;
            this.position = position;
        }

        @Override
        public String next() {
            return ids.get(position++ % ids.size());
        }

        @Override
        public boolean accepts(String text) {
            return true;
        }

        @Override
        public IdGenerator copy() {
            return new CyclingState(ids, position);
        }
    }

    @Test
    void aPurgedIdIsNeverGeneratedAgainNeitherForAnEntityNorForARelationship() {
        ReferenceStore cycling = new ReferenceStore(new CyclingState(List.of("p1", "p2", "r1", "p1", "p2", "r1", "x",
                "y", "z"), 0));
        Receipt first = cycling.commit(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "Alice"))
                .create(BETA, "Organization", from(1, 1), values("name", "Beta"))
                .relate(BETA_EMPLOYS_ALICE, "Employs", BETA, ALICE, from(1, 1), values()).build());
        EntityId alice = entity(first, ALICE);
        EntityId beta = entity(first, BETA);
        RelationshipId employs = relationship(first, BETA_EMPLOYS_ALICE);
        assertThat(List.of(alice.value(), beta.value(), employs.value())).containsExactly("p1", "p2", "r1");

        purged(cycling.store.purge(purge(alice)));
        Receipt second = cycling.commit(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "Carol"))
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .relate(BETA_EMPLOYS_ALICE, "Employs", ACME, ALICE, from(1, 1), values()).build());

        assertThat(second.created()).extracting(id -> id.value())
                .as("p1 and r1 were purged, and p2 is held by Beta").containsExactly("x", "y", "z");
        assertThat(cycling.view(on(3, 31)).entity(beta)).isPresent();
    }

    @Test
    void aSuppliedIdOfAPurgedRelationshipIsRefusedToo() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(reference.replay(ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        purgeNow(alice);
        EntityId carol = ReferenceStore.id(40);

        Transaction transaction = Transaction.by("importer")
                .create(carol, "Person", from(1, 1), values("name", "Carol"))
                .relate(employs, "Employs", beta, carol, from(1, 1), values()).build();
        List<Problem> problems = reference.reject(transaction);

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.ENTITY_EXISTS);
            assertThat(problem.path()).isEqualTo(employs.value());
            assertThat(problem.message()).contains("purged");
        });
        assertThat(reference.store.dryRun(transaction)).isInstanceOf(DryRunResult.Rejected.class);
    }

    // ------------------------------------------------------------------ idempotency keys

    @Test
    void onlyTheKeysOfTransactionsThatWrotePurgedDataAreRefusedAfterAPurge() {
        ReferenceStories.Recording rd1 = ReferenceStories.rd1Parties();
        Transaction parties = keyed("parties", rd1.transaction());
        Receipt partiesReceipt = reference.replay(new ReferenceStories.Recording(rd1.recordedAt(), parties));
        Transaction bob = keyed("bob", Transaction.by("importer")
                .create("Person", from(1, 1), values("name", "Bob")).build());
        Receipt bobReceipt = reference.commit(bob);
        Transaction renamed = keyed("renamed", Transaction.by("importer")
                .patch(entity(partiesReceipt, ALICE), from(2, 1), values("name", "Alice Smith")).build());
        reference.commit(renamed);
        Transaction otherTransaction = keyed("other", Transaction.by("importer")
                .patch(entity(partiesReceipt, ACME), from(2, 1), values("name", "ACME Two")).build());
        Receipt otherReceipt = reference.commit(otherTransaction);
        purgeNow(entity(partiesReceipt, ALICE));
        Basis before = reference.store.latestBasis();

        assertThat(commitReceipt(reference.store.commit(bob))).as("Bob's key still gives its receipt")
                .isEqualTo(bobReceipt);
        assertThat(commitReceipt(reference.store.commit(otherTransaction))).as("the key of ACME's patch stays")
                .isEqualTo(otherReceipt);
        for (Transaction purgedTransaction : List.of(parties, renamed)) {
            String key = purgedTransaction.idempotencyKey().orElseThrow();
            assertThat(problemsOf(reference.store.commit(purgedTransaction))).as(key).singleElement()
                    .satisfies(problem -> {
                        assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
                        assertThat(problem.path()).isEqualTo("@transaction");
                        assertThat(problem.message()).contains(key);
                    });
            DryRunResult dryRun = reference.store.dryRun(purgedTransaction);
            assertThat(dryRun).as(key + " in a dry run").isInstanceOf(DryRunResult.Rejected.class);
            assertThat(((DryRunResult.Rejected) dryRun).problems()).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED);
                assertThat(problem.path()).isEqualTo("@transaction");
            });
        }
        assertThat(reference.store.latestBasis()).as("nothing was recorded").isEqualTo(before);
    }

    @Test
    void aPurgedKeyIsRefusedWhateverTheTransactionIsAndTheKeyOfACascadeIsPurgedToo() {
        Receipt parties = reference.createParties();
        EntityId alice = entity(parties, ALICE);
        Receipt withAddress = reference.commit(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1),
                        values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .relate(HAS_ADDRESS, "HasAddress", alice, HOME, from(1, 1), values()).build());
        EntityId home = entity(withAddress, HOME);
        Transaction ending = keyed("ending", Transaction.by("importer").endDate(alice, on(9, 1)).build());
        Receipt ended = commitReceipt(reference.store.commit(ending));
        assertThat(ended.ended()).as("the address was ended by the cascade").contains(home);

        purgeNow(home);

        assertThat(problemsOf(reference.store.commit(ending))).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED));
        Transaction another = keyed("ending", Transaction.by("importer").reason("another")
                .create("Person", from(1, 1), values("name", "Carol")).build());
        assertThat(problemsOf(reference.store.commit(another))).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_PURGED));
    }

    // ------------------------------------------------------------------ the record

    @Test
    void purgesAreListedOldestFirstEachWithABasisAndATransactionTimeOfItsOwn() {
        Receipt parties = reference.createParties();
        EntityId alice = entity(parties, ALICE);
        EntityId acme = entity(parties, ACME);
        reference.recordedOn(on(3, 2));

        PurgeRecord first = purgeNow(alice).record();
        reference.store.commit(Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob")).build());
        reference.recordedOn(on(3, 2));
        PurgeRecord second = purged(reference.store.purge(Purge.by("dpo").onBehalfOf("DSR-2026-0043").reason("Closed")
                .entity(acme).build())).record();

        assertThat(reference.store.purges()).containsExactly(first, second);
        assertThat(first.entities()).isEqualTo(1);
        assertThat(first.relationships()).isZero();
        assertThat(first.basis()).isEqualTo(new Basis(3));
        assertThat(second.basis()).isEqualTo(new Basis(5));
        assertThat(first.transactionTime()).isEqualTo(on(3, 2));
        assertThat(second.transactionTime()).as("the sequence never goes back").isAfter(first.transactionTime());
        assertThat(second.onBehalfOf()).contains("DSR-2026-0043");
        assertThat(reference.store.latestBasis()).isEqualTo(new Basis(5));
        assertThat(reference.store.transactions()).extracting(TransactionRecord::basis)
                .containsExactly(new Basis(2), new Basis(4));
        assertThatThrownBy(() -> reference.store.purges().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theStoreTellsWhetherAnIdWasPurgedWithoutListingThePurgedIds() {
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        EntityId alice = entity(parties, ALICE);
        RelationshipId employs = relationship(reference.replay(ReferenceStories.rd3Employment(beta, alice)),
                BETA_EMPLOYS_ALICE);
        assertThat(reference.store.wasPurged(alice)).isFalse();

        purgeNow(alice);

        assertThat(reference.store.wasPurged(alice)).isTrue();
        assertThat(reference.store.wasPurged(employs)).as("the ID of a relationship too").isTrue();
        assertThat(reference.store.wasPurged(beta)).isFalse();
        assertThat(reference.store.wasPurged(ReferenceStore.id(99))).isFalse();
        assertThatThrownBy(() -> reference.store.wasPurged(null)).isInstanceOf(NullPointerException.class);
        assertThat(reference.store.purges()).singleElement().satisfies(record -> {
            assertThat(record.entities()).isEqualTo(1);
            assertThat(record.relationships()).isEqualTo(1);
        });
    }

    @Test
    void aPurgeUsesTheNextTransactionTimeEvenIfTheClockStandsStill() {
        EntityId alice = entity(reference.createParties(), ALICE);
        Instant lastCommit = reference.store.transactions().getLast().transactionTime();
        reference.clock.moveTo(lastCommit);

        PurgeRecord record = purgeNow(alice).record();

        assertThat(record.transactionTime()).isAfter(lastCommit);
    }

    @Test
    void aViewKnownAtThePurgeTimeIsAtThePurgeBasis() {
        EntityId alice = entity(reference.createParties(), ALICE);
        reference.recordedOn(on(3, 2));
        PurgeRecord record = purgeNow(alice).record();

        View atPurge = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(record.transactionTime()));
        View beforePurge = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(3, 1)));

        assertThat(atPurge.basis()).isEqualTo(record.basis());
        assertThat(beforePurge.basis()).isEqualTo(new Basis(2));
        assertThat(beforePurge.entity(alice)).as("a purge changes every basis").isEmpty();
    }

    @Test
    void theNextCommitAfterAPurgeTakesTheNextBasis() {
        EntityId alice = entity(reference.createParties(), ALICE);
        PurgeRecord record = purgeNow(alice).record();

        Receipt next = reference.commit(Transaction.by("importer").create("Person", from(1, 1), values("name", "Bob"))
                .build());

        assertThat(next.basis().value()).isEqualTo(record.basis().value() + 1);
        assertThat(next.transactionTime()).isAfter(record.transactionTime());
        assertThat(next.created()).doesNotContain(alice);
    }

    @Test
    void aRejectedPurgeLeavesNoRecord() {
        reference.createParties();

        rejected(reference.store.purge(purge(ReferenceStore.id(99))));

        assertThat(reference.store.purges()).isEmpty();
        assertThat(reference.store.latestBasis()).isEqualTo(new Basis(2));
    }

    @Test
    void aPurgeRecordsThePartyActedFor() {
        EntityId alice = entity(reference.createParties(), ALICE);

        PurgeRecord record = purged(reference.store.purge(Purge.by("dpo").onBehalfOf("DSR-2026-0042")
                .reason("Erasure request").entity(alice).build())).record();

        assertThat(record.principal()).isEqualTo("dpo");
        assertThat(record.onBehalfOf()).contains("DSR-2026-0042");
        assertThat(record.reason()).isEqualTo("Erasure request");
        assertThat(new ArrayList<>(reference.store.purges())).containsExactly(record);
    }
}
