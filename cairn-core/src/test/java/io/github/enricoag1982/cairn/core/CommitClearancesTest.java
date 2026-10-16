package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withCheck;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withDerivedAttribute;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME_OWNS_BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A2;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.PRINCIPAL;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Commits and dry runs of transactions with a clearance, beyond the scenarios of the {@code attribute-access} spec
 * (ADR-0036, design D4 of {@code add-attribute-access-control}): creates, relates, patches that set and clear, replaces,
 * end-dates, a clearance that covers part of what a write names, rules, checks and derivations that see every value, and the
 * retry of a transaction with an idempotency key. The world is the reference domain with RD-1, RD-2, RD-5 and the LEI of
 * RD-9, with the classifications of {@link ClassifiedModel}. The tests run against RocksDB stores too (see
 * {@link TestBackend}).
 */
class CommitClearancesTest {

    private static final Clearance NONE = Clearance.none();

    private final ReferenceStore reference = ReferenceStore.with(ClassifiedModel.definition());
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;
    private EntityId a1;
    private EntityId a2;
    private RelationshipId owns;
    private RelationshipId heldByA2;

    @BeforeEach
    void theWorldOfRd1Rd2Rd5AndTheLei() {
        Receipt accounts = reference.createAccounts();
        acme = entity(reference.parties, ACME);
        beta = entity(reference.parties, BETA);
        alice = entity(reference.parties, ALICE);
        a1 = entity(accounts, A1);
        a2 = entity(accounts, A2);
        heldByA2 = relationship(accounts, HELD_BY_A2);
        owns = relationship(reference.replay(ReferenceStories.rd2Ownership(acme, beta)), ACME_OWNS_BETA);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(5, 2));
    }

    private static Transaction.Builder by(Clearance clearance) {
        return Transaction.by(PRINCIPAL).clearance(clearance);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private EntityState state(EntityId id) {
        return reference.view(on(5, 3)).entity(id).orElseThrow();
    }

    // ------------------------------------------------------------------ what a write sets

    @Test
    void aCreateThatSetsAWithheldAttributeIsRejectedAtTheAttributeOfTheWriteAndNothingIsRecorded() {
        Basis before = reference.store.latestBasis();

        List<Problem> problems = reference.reject(by(NONE)
                .create(GAMMA, "Organization", from(5, 1), values("name", "Gamma", "lei", "5493001KJTIIGC8Y1R13"))
                .build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).isEqualTo("$gamma.lei");
        assertThat(problem.message()).contains("lei", "Organization", "identity");
        assertThat(problem.expected()).hasValue("a transaction whose clearance covers identity");
        assertThat(problem.found()).hasValue("a clearance that covers no classification");
        assertThat(reference.store.latestBasis()).isEqualTo(before);
    }

    @Test
    void aCreateWithoutATemporaryIdIsRejectedAtThePositionOfTheWrite() {
        List<Problem> problems = reference.reject(by(NONE)
                .create("Organization", from(5, 1), values("name", "Gamma", "lei", "5493001KJTIIGC8Y1R13")).build());

        assertThat(only(problems).path()).isEqualTo("@write.0.lei");
    }

    @Test
    void aCreateThatSetsOnlyWhatIsNotWithheldIsCommitted() {
        Receipt receipt = reference.commit(by(NONE)
                .create(GAMMA, "Organization", from(5, 1), values("name", "Gamma")).build());

        EntityState gamma = state(entity(receipt, GAMMA));
        assertThat(gamma.values()).containsEntry("name", Value.of("Gamma"));
        assertThat(gamma.withheld()).as("as read through a view that is not restricted").isEmpty();
    }

    @Test
    void aRelateThatSetsAWithheldAttributeIsRejectedAtTheAttributeOfTheWrite() {
        List<Problem> problems = reference.reject(by(NONE)
                .relate(ReferenceStories.GAMMA_OWNS_BETA, "Owns", acme, alice, from(5, 1), values("share", 5)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).isEqualTo("$gammaOwnsBeta.share");
    }

    @Test
    void aPatchThatSetsAWithheldAttributeOfARelationshipIsRejected() {
        List<Problem> problems = reference.reject(by(NONE).patch(owns, from(5, 1), values("share", 50)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).isEqualTo(owns.value() + ".share");
    }

    @Test
    void everyWithheldAttributeOfAWriteIsReportedInTheOrderOfTheWrite() {
        List<Problem> problems = reference.reject(by(NONE)
                .patch(a1, from(5, 1), values("balance", money("1"), "currency", "CHF", "number", "A-9")).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.ACCESS_DENIED, a1.value() + ".balance"),
                tuple(ProblemCode.ACCESS_DENIED, a1.value() + ".currency"));
    }

    @Test
    void aWriteIsAlsoRejectedWhenAnotherWriteOfTheTransactionIsAllowed() {
        List<Problem> problems = reference.reject(by(NONE)
                .patch(alice, from(5, 1), values("name", "Alice Smith"))
                .patch(a1, from(5, 1), values("balance", money("1"))).build());

        assertThat(only(problems).path()).isEqualTo(a1.value() + ".balance");
        assertThat(state(alice).values()).containsEntry("name", Value.of("Alice"));
    }

    // ------------------------------------------------------------------ what a write names

    private static final UniqueKey ACME_BY_LEI = new UniqueKey("Organization", "lei", Value.of(LEI));
    private static final UniqueKey NOBODY_BY_LEI = new UniqueKey("Organization", "lei",
            Value.of("5493001KJTIIGC8Y1R13"));

    @Test
    void everyKindOfWriteIsRefusedForAKeyOfAWithheldAttributeWhetherOrNotAnEntityHasTheValue() {
        for (UniqueKey key : List.of(ACME_BY_LEI, NOBODY_BY_LEI)) {
            Basis before = reference.store.latestBasis();
            List<Transaction> writes = List.of(
                    by(NONE).patch(key, from(5, 1), values("name", "ACME Holdings")).build(),
                    by(NONE).replace(key, from(5, 1), values("name", "ACME Holdings")).build(),
                    by(NONE).endDate(key, on(6, 1)).build());

            for (Transaction write : writes) {
                Problem problem = only(reference.reject(write));
                assertThat(problem.code()).as("%s", write.writes().getFirst()).isEqualTo(ProblemCode.ACCESS_DENIED);
                assertThat(problem.path()).isEqualTo("@write.0");
                assertThat(problem.expected()).hasValue("a transaction whose clearance covers identity, or a reference "
                        + "by ID");
                assertThat(problem.found()).hasValue("a clearance that covers no classification");
                assertThat(((DryRunResult.Rejected) reference.store.dryRun(write)).problems()).containsExactly(problem);
            }
            assertThat(reference.store.latestBasis()).isEqualTo(before);
        }
    }

    @Test
    void theEndsOfARelationshipAreRefusedToo() {
        Problem source = only(reference.reject(by(NONE).relate("Employs", ACME_BY_LEI, alice, from(5, 1), values())
                .build()));
        Problem target = only(reference.reject(by(Clearance.of("ownership")).relate("Owns", acme, ACME_BY_LEI, from(5, 1),
                values("share", 5)).build()));

        assertThat(source.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(source.path()).isEqualTo("@write.0");
        assertThat(target.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(target.path()).isEqualTo("@write.0");
    }

    @Test
    void aKeyIsRefusedBeforeAnythingElseOfTheWriteIsReported() {
        List<Problem> problems = reference.reject(by(NONE).patch(ACME_BY_LEI, from(5, 1),
                values("name", "ACME Holdings", "nothing", 1)).build());

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.ACCESS_DENIED);
    }

    @Test
    void aClearanceThatCoversTheKeysAttributeResolvesTheKey() {
        Receipt receipt = reference.commit(by(Clearance.of("identity")).patch(ACME_BY_LEI, from(5, 1),
                values("name", "ACME Holdings")).build());

        assertThat(receipt.keys()).singleElement().satisfies(resolution -> {
            assertThat(resolution.key()).isEqualTo(ACME_BY_LEI);
            assertThat(resolution.entity()).isEqualTo(acme);
        });
        assertThat(state(acme).values()).containsEntry("name", Value.of("ACME Holdings"));
    }

    @Test
    void aClearanceOfAnotherClassificationDoesNotCoverTheKeysAttribute() {
        Problem problem = only(reference.reject(by(Clearance.of("finance", "ownership"))
                .patch(ACME_BY_LEI, from(5, 1), values("name", "ACME Holdings")).build()));

        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.found()).hasValue("a clearance that covers finance, ownership");
    }

    @Test
    void aKeyOfAnAttributeWithoutAClassificationIsResolvedWhateverTheClearance() {
        Receipt receipt = reference.commit(by(NONE).patch(new UniqueKey("Account", "number", Value.of("A-1")),
                from(5, 1), values("number", "A-1")).build());

        assertThat(receipt.keys()).singleElement().satisfies(resolution -> assertThat(resolution.entity()).isEqualTo(a1));
    }

    @Test
    void aKeyThatTheSchemaDoesNotAllowIsStillReportedAsSuchAndAKeyByIdIsNotAffected() {
        Problem unknown = only(reference.reject(by(NONE).patch(new UniqueKey("Organization", "nothing", Value.of("x")),
                from(5, 1), values()).build()));
        reference.commit(by(NONE).patch(acme, from(5, 1), values("name", "ACME Holdings")).build());

        assertThat(unknown.code()).isEqualTo(ProblemCode.INVALID_UNIQUE_KEY);
        assertThat(state(acme).values()).containsEntry("name", Value.of("ACME Holdings"));
    }

    @Test
    void aTransactionWithoutAClearanceResolvesAKeyOfAClassifiedAttributeAsBefore() {
        Receipt receipt = reference.commit(Transaction.by(PRINCIPAL).patch(ACME_BY_LEI, from(5, 1),
                values("name", "ACME Holdings")).build());

        assertThat(receipt.keys()).hasSize(1);
    }

    // ------------------------------------------------------------------ what a write clears

    @Test
    void aPatchThatClearsAWithheldAttributeIsRejectedAndTheValueStays() {
        List<Problem> problems = reference.reject(by(NONE)
                .patch(acme, from(5, 1), values(), Set.of("lei"), ConflictCheck.none())
                .build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).isEqualTo(acme.value() + ".lei");
        assertThat(problem.message()).contains("cannot clear");
        assertThat(state(acme).values()).containsEntry("lei", Value.of(LEI));
    }

    @Test
    void aPatchThatSetsAndClearsTheSameWithheldAttributeIsReportedOnce() {
        List<Problem> problems = reference.reject(by(NONE).patch(acme, from(5, 1), values("lei", LEI),
                Set.of("lei"), ConflictCheck.none()).build());

        assertThat(only(problems).path()).isEqualTo(acme.value() + ".lei");
    }

    @Test
    void aPatchThatClearsWhatIsNotWithheldIsCommitted() {
        reference.commit(by(NONE).patch(alice, from(5, 1), values("birthDate", LocalDate.of(1990, 1, 1)))
                .build());

        reference.commit(by(NONE).patch(alice, from(5, 1), values(), Set.of("birthDate"),
                ConflictCheck.none()).build());

        assertThat(state(alice).values()).doesNotContainKey("birthDate");
    }

    // ------------------------------------------------------------------ replaces and end-dates

    @Test
    void aReplaceThatNamesAWithheldAttributeIsRejected() {
        List<Problem> problems = reference.reject(by(NONE).replace(acme, from(5, 1),
                values("name", "ACME Holdings", "lei", LEI)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(problem.path()).isEqualTo(acme.value() + ".lei");
    }

    @Test
    void aReplaceThatLeavesAWithheldAttributeOutKeepsItsValue() {
        reference.commit(by(NONE).replace(acme, from(5, 1), values("name", "ACME Holdings")).build());

        EntityState after = state(acme);
        assertThat(after.values()).containsEntry("name", Value.of("ACME Holdings")).containsEntry("lei", Value.of(LEI));
        assertThat(reference.view(on(4, 1)).entity(acme).orElseThrow().values()).as("before the replace")
                .containsEntry("name", Value.of("ACME")).containsEntry("lei", Value.of(LEI));
    }

    @Test
    void aReplaceOfAnAccountKeepsEveryWithheldValueAndReplacesTheRest() {
        reference.commit(by(NONE).replace(a1, from(5, 1), values("number", "A-1")).build());

        assertThat(state(a1).values()).containsEntry("number", Value.of("A-1"))
                .containsEntry("currency", Value.of("EUR")).containsEntry("balance", Value.of(money("1000")));
    }

    @Test
    void aReplaceOfARelationshipKeepsItsWithheldShare() {
        reference.commit(by(NONE).replace(owns, from(5, 1), values()).build());

        assertThat(reference.view(on(5, 3)).relationship(owns).orElseThrow().values())
                .containsEntry("share", Value.of(new BigDecimal("60")));
    }

    @Test
    void aReplaceThroughAClearanceThatCoversTheAttributeReplacesIt() {
        List<Problem> problems = reference.reject(by(Clearance.of("identity")).replace(a1, from(5, 1),
                values("number", "A-1", "currency", "EUR", "balance", money("1"))).build());

        assertThat(problems).extracting(Problem::path).containsExactly(a1.value() + ".currency",
                a1.value() + ".balance");
    }

    @Test
    void anEndDateWritesNoValuesAndIsAllowedForAnEntityWithWithheldValues() {
        reference.commit(by(NONE).endDate(a2, on(6, 1)).endDate(heldByA2, on(6, 1)).build());

        assertThat(reference.view(on(6, 2)).entity(a2)).isEmpty();
        assertThat(reference.view(on(5, 3)).entity(a2).orElseThrow().values()).containsKey("balance");
    }

    // ------------------------------------------------------------------ clearances that cover

    @Test
    void aClearanceThatCoversTheAttributeLetsTheWriteThrough() {
        reference.commit(by(Clearance.of("finance")).patch(a1, from(5, 1), values("balance", money("1500"))).build());

        assertThat(state(a1).values()).containsEntry("balance", Value.of(money("1500")));
    }

    @Test
    void aClearanceThatCoversPartOfWhatAWriteNamesRejectsTheRest() {
        List<Problem> problems = reference.reject(by(Clearance.of("finance")).patch(a1, from(5, 1),
                values("balance", money("1500"), "currency", "CHF")).build());

        assertThat(only(problems).path()).isEqualTo(a1.value() + ".currency");
        assertThat(only(problems).found()).hasValue("a clearance that covers finance");
    }

    @Test
    void aClearanceOfAnotherClassificationDoesNotCover() {
        List<Problem> problems = reference.reject(by(Clearance.of("identity", "ownership"))
                .patch(a1, from(5, 1), values("balance", money("1500"))).build());

        assertThat(only(problems).code()).isEqualTo(ProblemCode.ACCESS_DENIED);
    }

    @Test
    void aTransactionWithoutAClearanceWritesEveryAttributeAsBefore() {
        reference.commit(Transaction.by(PRINCIPAL).patch(a1, from(5, 1), values("balance", money("1500"),
                "currency", "CHF")).patch(acme, from(5, 1), values(), Set.of("lei"),
                ConflictCheck.none()).build());

        assertThat(state(a1).values()).containsEntry("balance", Value.of(money("1500")))
                .containsEntry("currency", Value.of("CHF"));
        assertThat(state(acme).values()).doesNotContainKey("lei");
    }

    @Test
    void aClearanceThatCoversNothingStillWritesWhatIsNotClassified() {
        reference.commit(by(NONE).patch(alice, from(5, 1), values("name", "Alice Smith"))
                .patch(a1, from(5, 1), values("number", "A-1")).build());

        assertThat(state(alice).values()).containsEntry("name", Value.of("Alice Smith"));
    }

    // ------------------------------------------------------------------ dry runs

    @Test
    void aDryRunReportsTheSameProblemsAsACommit() {
        Transaction denied = by(NONE).patch(a1, from(5, 1), values("balance", money("1500"))).build();
        Transaction allowed = by(NONE).patch(alice, from(5, 1), values("name", "Alice Smith")).build();

        DryRunResult dryDenied = reference.store.dryRun(denied);
        DryRunResult dryAllowed = reference.store.dryRun(allowed);
        CommitResult committedDenied = reference.store.commit(denied);

        assertThat(dryDenied).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) dryDenied).problems())
                .isEqualTo(((CommitResult.Rejected) committedDenied).problems());
        assertThat(only(((DryRunResult.Rejected) dryDenied).problems()).code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(dryAllowed).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(state(alice).values()).as("a dry run records nothing").containsEntry("name", Value.of("Alice"));
    }

    @Test
    void aDryRunOfAClearanceThatCoversWouldCommit() {
        assertThat(reference.store.dryRun(by(Clearance.of("finance"))
                .patch(a1, from(5, 1), values("balance", money("1500"))).build()))
                .isInstanceOf(DryRunResult.WouldCommit.class);
    }

    // ------------------------------------------------------------------ rules, checks and derivations

    @Test
    void aRuleSeesTheValuesThatTheClearanceDoesNotCover() {
        ReferenceStore funded = ReferenceStore.withRules(
                withClassification(AccountFundedRule.model(), "Account", "balance", "finance"),
                ReferenceStore.rulesWith(new AccountFundedRule()), ReferenceModel.declaredRules());
        Receipt accounts = funded.createAccounts();
        EntityId fundedAcme = entity(funded.parties, ACME);
        EntityId fundedAlice = entity(funded.parties, ALICE);
        funded.recordedOn(on(5, 2));

        funded.commit(by(NONE).relate("Employs", fundedAcme, fundedAlice, from(5, 1), values("title", "Analyst"))
                .build());

        assertThat(entity(accounts, A1)).isNotNull();
        assertThat(funded.view(on(5, 3)).navigate(fundedAcme, "Employs")).as("A-1 has 1,000, which the rule read")
                .hasSize(1);
    }

    @Test
    void aCheckSeesTheValuesThatTheClearanceDoesNotCover() {
        SchemaDefinition model = withCheck(ClassifiedModel.definition(), "Account", "x-has-1000",
                "!self.number.startsWith('X-') || self.balance >= 1000.0",
                "An account numbered X- has a balance of at least 1,000.");
        ReferenceStore checked = ReferenceStore.with(model);
        Receipt accounts = checked.createAccounts();
        EntityId checkedA1 = entity(accounts, A1);
        EntityId checkedA2 = entity(accounts, A2);
        checked.recordedOn(on(5, 2));
        checked.commit(Transaction.by(PRINCIPAL).patch(checkedA1, from(5, 1), values("balance", money("500"))).build());
        checked.recordedOn(on(5, 3));

        Receipt funded = checked.commit(by(NONE).patch(checkedA2, from(5, 1), values("number", "X-2")).build());
        List<Problem> unfunded = checked.reject(by(NONE).patch(checkedA1, from(5, 1), values("number", "X-1")).build());

        assertThat(funded).as("the check read the balance of 2,000, or it would have failed").isNotNull();
        assertThat(checked.view(on(5, 4)).entity(checkedA2).orElseThrow().values())
                .containsEntry("number", Value.of("X-2"));
        assertThat(only(unfunded).code()).as("the check read the balance of 500, or it would have failed")
                .isEqualTo(ProblemCode.CHECK_VIOLATED);
    }

    @Test
    void aDerivationSeesTheValuesThatTheClearanceDoesNotCoverAndIsWithheldWithThem() {
        SchemaDefinition model = withClassification(withDerivedAttribute(ClassifiedModel.definition(), "Account",
                "balanceInCents", ValueType.LONG, false, "int(self.balance * 100.0)"), "Account", "balanceInCents",
                "finance");
        ReferenceStore derived = ReferenceStore.with(model);
        EntityId derivedA1 = entity(derived.createAccounts(), A1);
        derived.recordedOn(on(5, 2));

        derived.commit(by(NONE).patch(derivedA1, from(5, 1), values("number", "A-1b")).build());

        EntityState open = derived.view(on(5, 3)).entity(derivedA1).orElseThrow();
        EntityState none = derived.view(on(5, 3)).restrictedTo(NONE).entity(derivedA1).orElseThrow();
        assertThat(open.values()).containsEntry("balanceInCents", Value.of(100_000L));
        assertThat(none.values()).doesNotContainKey("balanceInCents");
        assertThat(none.withheld()).containsExactly("currency", "balance", "balanceInCents");
    }

    @Test
    void aWriteOfADerivedAttributeIsStillADerivedAttributeWritten() {
        SchemaDefinition model = withClassification(withDerivedAttribute(ClassifiedModel.definition(), "Account",
                "balanceInCents", ValueType.LONG, false, "int(self.balance * 100.0)"), "Account", "balanceInCents",
                "finance");
        ReferenceStore derived = ReferenceStore.with(model);
        EntityId derivedA1 = entity(derived.createAccounts(), A1);

        List<Problem> problems = derived.reject(by(NONE).patch(derivedA1, from(5, 1),
                values("balanceInCents", 5L)).build());

        assertThat(only(problems).code()).isEqualTo(ProblemCode.DERIVED_ATTRIBUTE_WRITTEN);
    }

    // ------------------------------------------------------------------ retries

    private Transaction keyed(Clearance clearance) {
        Transaction.Builder builder = Transaction.by(PRINCIPAL).idempotencyKey("rename-alice");
        if (clearance != null) {
            builder.clearance(clearance);
        }
        return builder.patch(alice, from(5, 1), values("name", "Alice Smith")).build();
    }

    @Test
    void aRetryWithTheSameClearanceReturnsTheFirstReceipt() {
        Receipt first = reference.commit(keyed(Clearance.of("finance", "identity")));

        CommitResult second = reference.store.commit(keyed(Clearance.of("identity", "finance")));

        assertThat(second).as("the order of the classifications does not matter")
                .isEqualTo(new CommitResult.Committed(first));
        assertThat(reference.store.dryRun(keyed(Clearance.of("finance", "identity"))))
                .isInstanceOf(DryRunResult.WouldCommit.class);
    }

    @Test
    void aRetryWithAnotherClearanceIsAnotherTransactionWithTheSameKey() {
        reference.commit(keyed(Clearance.of("finance")));

        for (Clearance other : new Clearance[] {Clearance.of("identity"), Clearance.none(),
            Clearance.of("finance", "identity"), null}) {
            CommitResult retry = reference.store.commit(keyed(other));
            DryRunResult dry = reference.store.dryRun(keyed(other));

            assertThat(retry).as("commit with %s", other).isInstanceOf(CommitResult.Rejected.class);
            assertThat(((CommitResult.Rejected) retry).problems()).singleElement()
                    .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
            assertThat(dry).as("dry run with %s", other).isInstanceOf(DryRunResult.Rejected.class);
        }
    }

    @Test
    void aTransactionWithNoClearanceAndOneWithAClearanceThatCoversNothingAreNotTheSame() {
        reference.commit(keyed(null));

        CommitResult retry = reference.store.commit(keyed(Clearance.none()));

        assertThat(retry).isInstanceOf(CommitResult.Rejected.class);
        assertThat(keyed(null)).isNotEqualTo(keyed(Clearance.none()));
        assertThat(reference.store.commit(keyed(null))).isInstanceOf(CommitResult.Committed.class);
    }
}
