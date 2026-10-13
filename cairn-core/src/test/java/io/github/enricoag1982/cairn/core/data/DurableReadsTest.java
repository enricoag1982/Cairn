package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1_MOVED;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ControllingOwnerHasLeiRule;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a durable store learns after recovery (design D5 of {@code add-rule-rechecks}, ADR-0025): the rules it has code
 * for run once over every version whose type names them, to record what they read, and what they report is dropped.
 * Re-checks then behave as in a store that was never closed.
 *
 * <p>The store has the reference model in which {@code Employs} names {@code employer-holds-account} and {@code Owns}
 * names {@code controlling-owner-has-lei}. RD-1, RD-5, RD-9 (ACME's LEI), RD-2 and RD-3 are recorded in it.
 */
class DurableReadsTest {

    private static final RuleReference EMPLOYER_RULE = new RuleReference("employer-holds-account", 1);

    @TempDir
    Path directory;

    private DurableFixture durable;
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;
    private EntityId a1;
    private RelationshipId heldByA1;
    private RelationshipId owns;
    private RelationshipId employs;

    @BeforeEach
    void setUp() {
        durable = new DurableFixture(directory);
    }

    @AfterEach
    void tearDown() {
        durable.closeAll();
    }

    private static SchemaDefinition model() {
        return Definitions.withRule(Definitions.withRule(ReferenceModel.definition(), "Employs",
                EmployerHoldsAccountRule.DESCRIPTOR), "Owns", ControllingOwnerHasLeiRule.DESCRIPTOR);
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        rules.add(new EmployerHoldsAccountRule());
        rules.add(new ControllingOwnerHasLeiRule());
        return rules;
    }

    private DirectoryStore open() {
        return durable.open(durable.clock, IdGenerators.deterministic(), rules(), List.of());
    }

    /** Registers the model and records the stories, in the given store, with the given clock. */
    private void tell(Store store, TestClock clock) {
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), model()))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        acme = ReferenceTales.entity(parties, ACME);
        beta = ReferenceTales.entity(parties, BETA);
        alice = ReferenceTales.entity(parties, ALICE);
        Receipt accounts = ReferenceStories.replay(store, clock, ReferenceStories.rd5Accounts(beta, acme));
        a1 = ReferenceTales.entity(accounts, A1);
        heldByA1 = ReferenceTales.relationship(accounts, HELD_BY_A1);
        ReferenceStories.replay(store, clock, ReferenceStories.rd9AcmeLei(acme));
        owns = ReferenceTales.relationship(ReferenceStories.replay(store, clock, ReferenceStories.rd2Ownership(acme,
                beta)), ACME_OWNS_BETA);
        employs = ReferenceTales.relationship(ReferenceStories.replay(store, clock,
                ReferenceStories.rd3Employment(beta, alice)), BETA_EMPLOYS_ALICE);
    }

    /** Transactions that other writes can make a rule object to, and some that nothing objects to. */
    private List<Transaction> candidates() {
        return List.of(
                Transaction.by("tester").endDate(heldByA1, on(6, 1))
                        .relate(HELD_BY_A1_MOVED, "HeldBy", a1, acme, from(6, 1), values()).build(),
                Transaction.by("tester").patch(acme, from(6, 1), values(), Set.of("lei"), ConflictCheck.none())
                        .build(),
                Transaction.by("tester").patch(acme, from(8, 1), values("name", "ACME Holdings")).build(),
                Transaction.by("tester").endDate(employs, on(9, 1)).build(),
                Transaction.by("tester").endDate(owns, on(9, 1)).build(),
                Transaction.by("tester").endDate(heldByA1, on(6, 1)).build(),
                Transaction.by("tester").create(GAMMA, "Organization", from(4, 1), values("name", "Gamma"))
                        .relate("Owns", GAMMA, beta, from(4, 1), values("share", 50)).build(),
                Transaction.by("tester").patch(owns, from(7, 1), values("share", 70)).build(),
                Transaction.by("tester").patch(acme, EffectivePeriod.between(on(4, 1), on(4, 2)), values(),
                        Set.of("lei"), ConflictCheck.none()).build(),
                Transaction.by("tester").endDate(heldByA1, on(4, 15))
                        .relate(HELD_BY_A1_MOVED, "HeldBy", a1, acme, from(4, 15), values()).build());
    }

    /**
     * A write that splits the versions of Beta's employment of Alice and of ACME's ownership of Beta without changing
     * their values in the middle, and changes them from September, so that the timelines are recorded with the split.
     */
    private Transaction splitsWithoutChangingValues() {
        EffectivePeriod april = EffectivePeriod.between(on(4, 1), on(5, 1));
        return Transaction.by("tester")
                .patch(employs, april, values("title", "Temporary"))
                .patch(employs, april, values("title", "Analyst"))
                .patch(employs, from(9, 1), values("title", "Lead"))
                .patch(owns, april, values("share", 61))
                .patch(owns, april, values("share", 60))
                .patch(owns, from(9, 1), values("share", 65)).build();
    }

    private static DryRunResult dryRun(Store store, Transaction transaction) {
        return store.dryRun(transaction);
    }

    // ------------------------------------------------------------------ the index is learned after recovery

    @Test
    void aReopenedStoreHasLearnedWhatItsRulesRead() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        Set<String> before = first.readIndex().describe();
        assertThat(before).isNotEmpty();
        first.close();

        DirectoryStore reopened = open();

        assertThat(reopened.readIndex().describe()).as("the same reads").isEqualTo(before);
        assertThat(reopened.readIndex().consistent()).isTrue();
        assertThat(reopened.readIndex().checks(employs, EMPLOYER_RULE)).hasSize(1);
    }

    @Test
    void aStoreThatCrashedHasLearnedWhatItsRulesReadFromItsLog() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        Set<String> before = first.readIndex().describe();
        DurableFixture.crash(first);

        DirectoryStore reopened = open();

        assertThat(reopened.readIndex().describe()).isEqualTo(before);
    }

    @Test
    void aStoreRecoveredFromASnapshotFileAndTheLogAfterItHasLearnedWhatItsRulesRead() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        first.writeSnapshot();
        durable.replay(first, ReferenceStories.rd7Rename(acme));
        Set<String> before = first.readIndex().describe();
        DurableFixture.crash(first);

        DirectoryStore reopened = open();

        assertThat(reopened.readIndex().describe()).isEqualTo(before);
    }

    @Test
    void aReopenedStoreRejectsAndAcceptsWhatTheStoreItWasDid() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        List<DryRunResult> before = candidates().stream().map(transaction -> dryRun(first, transaction)).toList();
        assertThat(before).as("some are rejected by re-checks, some are not").anyMatch(
                result -> result instanceof DryRunResult.Rejected).anyMatch(
                result -> result instanceof DryRunResult.WouldCommit);
        first.close();

        DirectoryStore reopened = open();

        assertThat(candidates().stream().map(transaction -> dryRun(reopened, transaction)).toList())
                .isEqualTo(before);
    }

    @Test
    void aReopenedStoreBehavesAsAnInMemoryStoreThatRecordedTheSameStories() {
        TestClock memoryClock = new TestClock();
        InMemoryStore memory = new InMemoryStore(memoryClock, IdGenerators.deterministic(), rules(), List.of());
        tell(memory, memoryClock);
        DirectoryStore first = open();
        tell(first, durable.clock);
        // The live stores keep one check across a version that a write split without changing its values; a store that
        // is opened again learns one check for each part. The rules keep their contract, so the verdicts agree.
        Transaction split = splitsWithoutChangingValues();
        ReferenceStories.replay(memory, memoryClock, new ReferenceStories.Recording(on(5, 20), split));
        ReferenceStories.replay(first, durable.clock, new ReferenceStories.Recording(on(5, 20), split));
        assertThat(memory.view(Coordinates.effectiveAt(on(5, 1))).relationshipTimeline(employs))
                .as("the versions are split").hasSizeGreaterThan(2);
        assertThat(memory.readIndex().checks(employs, EMPLOYER_RULE)).as("one live check across the split part")
                .hasSizeLessThan(memory.view(Coordinates.effectiveAt(on(5, 1))).relationshipTimeline(employs).size());
        first.close();

        DirectoryStore reopened = open();

        assertThat(reopened.readIndex().checks(employs, EMPLOYER_RULE)).as("one check for each part")
                .hasSize(reopened.view(Coordinates.effectiveAt(on(5, 1))).relationshipTimeline(employs).size());
        for (Transaction transaction : candidates()) {
            assertThat(dryRun(reopened, transaction)).as(transaction.writes().toString())
                    .isEqualTo(dryRun(memory, transaction));
        }
    }

    @Test
    void aReopenedStoreKeepsFindingWhatEachTransactionAfterReopeningChanges() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        first.close();
        DirectoryStore reopened = open();

        // A transaction after reopening re-checks and records like one before: Beta gets a second account, and then
        // A-1 can move.
        TestClock memoryClock = new TestClock();
        InMemoryStore memory = new InMemoryStore(memoryClock, IdGenerators.deterministic(), rules(), List.of());
        tell(memory, memoryClock);
        Transaction secondAccount = Transaction.by("tester")
                .create(GAMMA, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance",
                        ReferenceStories.money("10")))
                .relate("HeldBy", GAMMA, beta, from(1, 1), values()).build();
        durable.clock.moveTo(on(5, 20));
        memoryClock.moveTo(on(5, 20));
        assertThat(reopened.commit(secondAccount)).isEqualTo(memory.commit(secondAccount));

        for (Transaction transaction : candidates()) {
            assertThat(dryRun(reopened, transaction)).isEqualTo(dryRun(memory, transaction));
        }
        assertThat(dryRun(reopened, candidates().getFirst())).as("A-1 can move now, as Beta holds A-3")
                .isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reopened.readIndex().describe()).isEqualTo(memory.readIndex().describe());
    }

    // ------------------------------------------------------------------ the rules decide only what is learned

    @Test
    void aRuleWhoseCodeTheStoreLacksRecordsNothing() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        int before = first.readIndex().size();
        first.close();

        DirectoryStore reopened = durable.open(durable.clock, IdGenerators.deterministic(),
                ReferenceModel.executableRules(), List.of(EmployerHoldsAccountRule.DESCRIPTOR,
                        ControllingOwnerHasLeiRule.DESCRIPTOR));

        assertThat(reopened.readIndex().size()).isLessThan(before);
        assertThat(reopened.readIndex().checks(employs, EMPLOYER_RULE)).isEmpty();
        assertThat(reopened.readIndex().describe()).noneMatch(line -> line.contains("employer-holds-account"))
                .noneMatch(line -> line.contains("controlling-owner-has-lei"));
    }

    @Test
    void whatTheRulesReportOrThrowWhileTheStoreLearnsDoesNotStopTheStoreFromOpening() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        Basis latest = first.latestBasis();
        first.close();
        AtomicInteger calls = new AtomicInteger();
        EntityRule failing = new EntityRule() {
            @Override
            public RuleDescriptor descriptor() {
                return LeiChecksumRule.DESCRIPTOR;
            }

            @Override
            public void check(EntityState version, RuleContext context) {
                calls.incrementAndGet();
                context.viewAt(on(6, 1)).entity(beta);
                context.problem("invalid-check-digits", "lei", "Every LEI is wrong.");
                throw new IllegalStateException("and the rule fails");
            }
        };
        List<Rule> rules = List.of(failing, new TotalOwnershipRule(), new EmployerHoldsAccountRule(),
                new ControllingOwnerHasLeiRule());

        DirectoryStore reopened = durable.open(durable.clock, IdGenerators.deterministic(), rules, List.of());

        assertThat(calls.get()).as("it ran for the organizations").isPositive();
        assertThat(reopened.readIndex().describe()).as("what it read before it failed is recorded")
                .anyMatch(line -> line.contains("lei-checksum") && line.contains(beta.value()));
        assertThat(reopened.latestBasis()).isEqualTo(latest);
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1)))
                .entities("Organization")).hasSize(2);
    }

    /** A rule for organizations that reads Beta, and then fails as the given supplier says. */
    private EntityRule failingAfterReadingBeta(Supplier<Error> failure, AtomicInteger calls) {
        return new EntityRule() {
            @Override
            public RuleDescriptor descriptor() {
                return LeiChecksumRule.DESCRIPTOR;
            }

            @Override
            public void check(EntityState version, RuleContext context) {
                calls.incrementAndGet();
                context.viewAt(on(6, 1)).entity(beta);
                throw failure.get();
            }
        };
    }

    /** Recurses until the stack of the thread is full. */
    private static int overflow(int depth) {
        return overflow(depth + 1) + 1;
    }

    @Test
    void aRuleThatOverflowsItsStackWhileTheStoreLearnsDoesNotStopTheStoreFromOpening() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        Basis latest = first.latestBasis();
        first.close();
        AtomicInteger calls = new AtomicInteger();
        EntityRule overflowing = failingAfterReadingBeta(() -> {
            overflow(0);
            return new StackOverflowError();
        }, calls);
        List<Rule> rules = List.of(overflowing, new TotalOwnershipRule(), new EmployerHoldsAccountRule(),
                new ControllingOwnerHasLeiRule());

        DirectoryStore reopened = durable.open(durable.clock, IdGenerators.deterministic(), rules, List.of());

        assertThat(calls.get()).as("it ran for the organizations").isPositive();
        assertThat(reopened.readIndex().describe()).as("what it read before the stack overflowed is recorded")
                .anyMatch(line -> line.contains("lei-checksum") && line.contains(beta.value()));
        assertThat(reopened.latestBasis()).isEqualTo(latest);
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).hasSize(2);
        assertThat(reopened.readIndex().checks(employs, EMPLOYER_RULE)).as("the other rules were learned")
                .hasSize(1);
    }

    @Test
    void aCommitThatMakesARuleOverflowItsStackStillLeavesTheStoreAsItWasAndLetsTheErrorGo() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        first.close();
        EntityRule overflowing = failingAfterReadingBeta(() -> {
            overflow(0);
            return new StackOverflowError();
        }, new AtomicInteger());
        List<Rule> rules = List.of(overflowing, new TotalOwnershipRule(), new EmployerHoldsAccountRule(),
                new ControllingOwnerHasLeiRule());
        DirectoryStore reopened = durable.open(durable.clock, IdGenerators.deterministic(), rules, List.of());
        Basis latest = reopened.latestBasis();

        assertThatThrownBy(() -> reopened.commit(Transaction.by("tester")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma")).build()))
                .isInstanceOf(StackOverflowError.class);

        assertThat(reopened.latestBasis()).isEqualTo(latest);
        assertThat(reopened.view(Coordinates.effectiveAt(on(2, 1))).entities("Organization")).hasSize(2);
    }

    @Test
    void aRuleThatRunsTheVirtualMachineOutOfMemoryWhileTheStoreLearnsStopsTheStoreFromOpening() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        Basis latest = first.latestBasis();
        first.close();
        EntityRule exhausting = failingAfterReadingBeta(() -> new OutOfMemoryError("as a test says"),
                new AtomicInteger());
        List<Rule> rules = List.of(exhausting, new TotalOwnershipRule(), new EmployerHoldsAccountRule(),
                new ControllingOwnerHasLeiRule());

        assertThatThrownBy(() -> durable.open(durable.clock, IdGenerators.deterministic(), rules, List.of()))
                .isInstanceOf(OutOfMemoryError.class);

        DirectoryStore again = open();
        assertThat(again.latestBasis()).as("the directory was released, and nothing was changed").isEqualTo(latest);
    }

    // ------------------------------------------------------------------ purges

    @Test
    void aPurgeLeavesNoPurgedIdInTheIndexOfTheStoreOrOfTheStoreThatReopensIt() {
        DirectoryStore first = open();
        tell(first, durable.clock);
        durable.clock.moveTo(on(10, 1));
        first.purge(Purge.by("dpo").reason("Erasure request").entity(a1).build());
        assertThat(first.readIndex().mentionedIds()).doesNotContain(a1.value(), heldByA1.value());
        Set<String> before = first.readIndex().describe();
        first.close();

        DirectoryStore reopened = open();

        assertThat(reopened.readIndex().mentionedIds()).doesNotContain(a1.value(), heldByA1.value());
        assertThat(reopened.readIndex().describe()).isEqualTo(before);
    }
}
