package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA_EMPLOYS_ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1_MOVED;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.data.Timeline.Stretch;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Purge;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.ControllingOwnerHasLeiRule;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.EmployerHoldsAccountRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the in-memory store keeps in its index of reads (design D3 of {@code add-rule-rechecks}): the checks that the
 * rules of committed transactions record, how writes and runs change their periods, and that a dry run, a rejected
 * transaction, a schema registration and a purge leave the index as the design says. The tests read the index of the
 * store directly, which a view never shows.
 *
 * <p>The store has the reference model in which {@code Employs} names {@code employer-holds-account} and {@code Owns}
 * names {@code controlling-owner-has-lei}, besides the rules of the reference model. {@code employer-holds-account}
 * is run through a rule that counts its calls.
 */
class ReadIndexStoreTest {

    private static final RuleReference EMPLOYER_RULE = new RuleReference("employer-holds-account", 1);
    private static final RuleReference OWNER_RULE = new RuleReference("controlling-owner-has-lei", 1);
    private static final RuleReference TOTAL_RULE = new RuleReference("total-ownership-at-most-100", 1);

    /** {@code employer-holds-account} that counts the versions it checks. */
    private static final class CountingEmployerRule implements RelationshipRule {
        final AtomicInteger calls = new AtomicInteger();
        private final EmployerHoldsAccountRule rule = new EmployerHoldsAccountRule();

        @Override
        public RuleDescriptor descriptor() {
            return rule.descriptor();
        }

        @Override
        public void check(RelationshipState version, RuleContext context) {
            calls.incrementAndGet();
            rule.check(version, context);
        }
    }

    private final TestClock clock = new TestClock();
    private final CountingEmployerRule employerRule = new CountingEmployerRule();
    private InMemoryStore store;
    private ReadIndex index;
    private Receipt parties;
    private Receipt accounts;
    private Receipt employment;
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;
    private EntityId a1;
    private RelationshipId heldByA1;
    private RelationshipId employs;

    private static SchemaDefinition model() {
        return Definitions.withRule(Definitions.withRule(ReferenceModel.definition(), "Employs",
                EmployerHoldsAccountRule.DESCRIPTOR), "Owns", ControllingOwnerHasLeiRule.DESCRIPTOR);
    }

    @BeforeEach
    void aStoreWithTheEmployerAtWork() {
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        rules.add(employerRule);
        rules.add(new ControllingOwnerHasLeiRule());
        store = new InMemoryStore(clock, IdGenerators.deterministic(), rules, List.of());
        index = store.readIndex();
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), model()))
                .isInstanceOf(RegistrationResult.Applied.class);
        parties = ReferenceStories.replay(store, clock, ReferenceStories.rd1Parties());
        acme = ReferenceTales.entity(parties, ACME);
        beta = ReferenceTales.entity(parties, BETA);
        alice = ReferenceTales.entity(parties, ALICE);
        accounts = ReferenceStories.replay(store, clock, ReferenceStories.rd5Accounts(beta, acme));
        a1 = ReferenceTales.entity(accounts, A1);
        heldByA1 = ReferenceTales.relationship(accounts, HELD_BY_A1);
        employment = ReferenceStories.replay(store, clock, ReferenceStories.rd3Employment(beta, alice));
        employs = ReferenceTales.relationship(employment, BETA_EMPLOYS_ALICE);
    }

    private TypeId relationshipType(String name) {
        return store.schema().current().relationshipType(name).orElseThrow().id();
    }

    private List<Stretch> periodsOf(RelationshipId subject, RuleReference rule) {
        return index.checks(subject, rule).stream().flatMap(check -> check.periods().stream()).toList();
    }

    private static Transaction movesA1(EntityId a1, RelationshipId heldBy, EntityId newHolder) {
        return Transaction.by("tester").endDate(heldBy, on(6, 1))
                .relate(HELD_BY_A1_MOVED, "HeldBy", a1, newHolder, from(6, 1), values()).build();
    }

    // ------------------------------------------------------------------ what a commit records

    @Test
    void aCommitRecordsTheReadsOfTheRulesItRanAsKeysAndWhenTheyWereRead() {
        List<ReadIndex.Check> checks = index.checks(employs, EMPLOYER_RULE);

        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.periods()).containsExactly(new Stretch(on(3, 1), Segment.OPEN));
            assertThat(check.reads().keySet()).containsExactlyInAnyOrder(
                    new ReadKey.Navigation(beta, relationshipType("HeldBy"), false),
                    new ReadKey.Relationship(heldByA1), new ReadKey.Entity(a1));
            assertThat(check.reads().get(new ReadKey.Navigation(beta, relationshipType("HeldBy"), false))
                    .acrossTime()).as("the rule read the accounts across effective time").isTrue();
            assertThat(check.reads().get(new ReadKey.Entity(a1)).instants()).as("and the account at the start")
                    .containsExactly(on(3, 1));
        });
        assertThat(index.checks(employs, OWNER_RULE)).as("Employs does not name that rule").isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aRuleThatReadsNothingLeavesNoCheck() {
        // lei-checksum reads only the version it checks.
        assertThat(index.checks(acme, new RuleReference("lei-checksum", 1))).isEmpty();
        assertThat(index.mentionedIds()).as("the IDs that the index mentions").contains(employs.value(), beta.value())
                .doesNotContain(alice.value());
    }

    @Test
    void theRulesOfARelationshipThatTheTransactionRelatesRecordTheirReads() {
        ReferenceStories.replay(store, clock, ReferenceStories.rd9AcmeLei(acme));
        Receipt ownership = ReferenceStories.replay(store, clock, ReferenceStories.rd2Ownership(acme, beta));
        RelationshipId owns = ReferenceTales.relationship(ownership, ACME_OWNS_BETA);

        assertThat(index.checks(owns, TOTAL_RULE)).singleElement().satisfies(check ->
                assertThat(check.reads().keySet()).contains(new ReadKey.Navigation(beta, relationshipType("Owns"),
                        false)));
        assertThat(index.checks(owns, OWNER_RULE)).as("60% is more than 50%, so the owner is read").singleElement()
                .satisfies(check -> assertThat(check.reads().keySet()).contains(new ReadKey.Entity(acme)));
    }

    // ------------------------------------------------------------------ dry runs and rejected transactions

    @Test
    void aDryRunLeavesTheIndexAsItWas() {
        Set<String> before = index.describe();
        int checks = index.size();

        assertThat(store.dryRun(Transaction.by("tester").patch(employs, from(4, 1), values("title", "Lead"))
                .build())).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(store.dryRun(movesA1(a1, heldByA1, acme))).as("rejected by a re-check")
                .isInstanceOf(DryRunResult.Rejected.class);

        assertThat(index.describe()).isEqualTo(before);
        assertThat(index.size()).isEqualTo(checks);
        assertThat(periodsOf(employs, EMPLOYER_RULE)).containsExactly(new Stretch(on(3, 1), Segment.OPEN));
    }

    @Test
    void aRejectedTransactionLeavesTheIndexAsItWas() {
        Set<String> before = index.describe();
        Basis basis = store.latestBasis();

        CommitResult byARecheck = store.commit(movesA1(a1, heldByA1, acme));
        CommitResult byARuleOfTheWrite = store.commit(Transaction.by("tester")
                .patch(employs, from(4, 1), values("title", "Lead"))
                .create("Organization", from(1, 1), values("name", "Gamma", "lei", ReferenceStories.LEI_WRONG_CHECK_DIGITS))
                .build());

        assertThat(byARecheck).isInstanceOf(CommitResult.Rejected.class);
        assertThat(byARuleOfTheWrite).isInstanceOf(CommitResult.Rejected.class);
        assertThat(store.latestBasis()).isEqualTo(basis);
        assertThat(index.describe()).isEqualTo(before);
        assertThat(periodsOf(employs, EMPLOYER_RULE)).containsExactly(new Stretch(on(3, 1), Segment.OPEN));
    }

    // ------------------------------------------------------------------ runs and writes change the periods

    @Test
    void aCommitThatRunsARuleForPartOfAVersionLeavesTheRestOfTheOlderCheck() {
        clock.moveTo(on(5, 20));

        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 20), Transaction.by("tester")
                .patch(employs, from(4, 1), values("title", "Lead")).build()));

        assertThat(periodsOf(employs, EMPLOYER_RULE)).containsExactlyInAnyOrder(
                new Stretch(on(3, 1), on(4, 1)), new Stretch(on(4, 1), Segment.OPEN));
        assertThat(index.checks(employs, EMPLOYER_RULE)).hasSize(2);
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aWriteThatEndsARelationshipCutsTheEndOffItsChecks() {
        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 20), Transaction.by("tester")
                .endDate(employs, on(9, 1)).build()));

        assertThat(periodsOf(employs, EMPLOYER_RULE)).containsExactly(new Stretch(on(3, 1), on(9, 1)));
    }

    @Test
    void aCheckWhosePeriodIsEntirelyCutOffIsDroppedWithItsReads() {
        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 20), Transaction.by("tester")
                .patch(employs, from(4, 1), values("title", "Lead")).build()));
        assertThat(index.checks(employs, EMPLOYER_RULE)).hasSize(2);

        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 21), Transaction.by("tester")
                .endDate(employs, on(4, 1)).build()));

        assertThat(periodsOf(employs, EMPLOYER_RULE)).containsExactly(new Stretch(on(3, 1), on(4, 1)));
        assertThat(index.checks(employs, EMPLOYER_RULE)).hasSize(1);
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aWriteThatSplitsAVersionWithoutChangingItsValuesCutsNothing() {
        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 20), Transaction.by("tester")
                .patch(employs, EffectivePeriod.between(on(4, 1), on(5, 1)), values("title", "Temporary"))
                .patch(employs, EffectivePeriod.between(on(4, 1), on(5, 1)), values("title", "Analyst"))
                .patch(employs, from(9, 1), values("title", "Lead")).build()));

        assertThat(periodsOf(employs, EMPLOYER_RULE)).as("the split is no change; the change from September is")
                .containsExactlyInAnyOrder(new Stretch(on(3, 1), on(9, 1)), new Stretch(on(9, 1), Segment.OPEN));
    }

    @Test
    void aRecheckReplacesTheCheckItRuns() {
        TemporaryId a3 = new TemporaryId("a3");
        Receipt more = ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 20),
                Transaction.by("tester")
                        .create(a3, "Account", from(1, 1), values("number", "A-3", "currency", "EUR", "balance",
                                ReferenceStories.money("10")))
                        .relate(new TemporaryId("heldBy3"), "HeldBy", a3, beta, from(1, 1), values()).build()));
        EntityId accountA3 = ReferenceTales.entity(more, a3);
        employerRule.calls.set(0);

        // Beta keeps A-3 when A-1 moves to ACME: the rule runs again, is content, and its check is made again.
        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 21), movesA1(a1, heldByA1, acme)));

        assertThat(employerRule.calls).as("the rule ran again, once").hasValue(1);
        assertThat(index.checks(employs, EMPLOYER_RULE)).singleElement().satisfies(check -> {
            assertThat(check.periods()).containsExactly(new Stretch(on(3, 1), Segment.OPEN));
            assertThat(check.reads().get(new ReadKey.Entity(accountA3)).instants()).as("it read the new account too")
                    .isNotEmpty();
        });
        assertThat(index.consistent()).isTrue();
    }

    // ------------------------------------------------------------------ a schema registration that removes the rule

    @Test
    void aCheckWhoseRuleItsTypeNoLongerNamesIsDroppedWhenAChangeReachesItAndTheRuleIsNotRun() {
        assertThat(index.checks(employs, EMPLOYER_RULE)).hasSize(1);
        assertThat(store.schema().register(ReferenceModel.origin("1.1.0"), Definitions.withRule(
                ReferenceModel.definition(), "Owns", ControllingOwnerHasLeiRule.DESCRIPTOR)))
                .isInstanceOf(RegistrationResult.Applied.class);
        assertThat(index.checks(employs, EMPLOYER_RULE)).as("a registration runs no rules and drops nothing")
                .hasSize(1);
        employerRule.calls.set(0);

        ReferenceStories.replay(store, clock, new ReferenceStories.Recording(on(5, 20), movesA1(a1, heldByA1, acme)));

        assertThat(employerRule.calls).as("the type no longer names the rule").hasValue(0);
        assertThat(index.checks(employs, EMPLOYER_RULE)).isEmpty();
        assertThat(index.readers(new ReadKey.Relationship(heldByA1))).isEmpty();
        assertThat(index.consistent()).isTrue();
    }

    // ------------------------------------------------------------------ purges

    @Test
    void aPurgeLeavesNoPurgedIdInTheIndex() {
        ReferenceStories.replay(store, clock, ReferenceStories.rd9AcmeLei(acme));
        Receipt ownership = ReferenceStories.replay(store, clock, ReferenceStories.rd2Ownership(acme, beta));
        RelationshipId owns = ReferenceTales.relationship(ownership, ACME_OWNS_BETA);
        assertThat(index.mentionedIds()).contains(a1.value(), heldByA1.value(), employs.value(), owns.value(),
                acme.value());

        clock.moveTo(on(10, 1));
        store.purge(Purge.by("dpo").reason("Erasure request").entity(a1).build());

        assertThat(store.wasPurged(a1)).isTrue();
        assertThat(index.mentionedIds()).as("the account and its HeldBy").doesNotContain(a1.value(), heldByA1.value())
                .contains(employs.value(), beta.value());
        assertThat(index.checks(employs, EMPLOYER_RULE)).as("the check of Employs stays, with the keys that stay")
                .singleElement().satisfies(check -> assertThat(check.reads().keySet())
                        .containsExactly(new ReadKey.Navigation(beta, relationshipType("HeldBy"), false)));
        assertThat(index.consistent()).isTrue();
    }

    @Test
    void aPurgeDropsTheChecksOfTheEntitiesAndRelationshipsItRemoves() {
        clock.moveTo(on(10, 1));
        store.purge(ReferenceStories.rd11PurgeAlice(alice));

        assertThat(store.wasPurged(alice)).isTrue();
        assertThat(index.checks(employs, EMPLOYER_RULE)).as("Beta's employment of Alice is purged with her").isEmpty();
        assertThat(index.mentionedIds()).doesNotContain(alice.value(), employs.value());
        assertThat(index.consistent()).isTrue();
    }

    // ------------------------------------------------------------------ the reads of the index in a store that rejects writes

    @Test
    void noCheckOfTheIndexSurvivesInAStoreWithoutCodeForTheRule() {
        InMemoryStore without = new InMemoryStore(new TestClock(), IdGenerators.deterministic(),
                ReferenceModel.executableRules(), List.of(EmployerHoldsAccountRule.DESCRIPTOR,
                        ControllingOwnerHasLeiRule.DESCRIPTOR));

        assertThat(without.readIndex().size()).isZero();
        assertThat(without.readIndex().describe()).isEmpty();
    }
}
