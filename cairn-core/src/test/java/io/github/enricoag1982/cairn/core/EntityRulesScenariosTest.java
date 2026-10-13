package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.HELD_BY_A1_MOVED;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd12GammaWrongCheckDigits;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.NameNotBlankRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code entity-rules} spec, one test each. */
class EntityRulesScenariosTest {

    /** The reference model in which Party names the rule {@code name-not-blank}, as the rule's descriptor says. */
    private static final SchemaDefinition PARTY_NAMES_RULE = TestEntityRule.namedIn(ReferenceModel.definition(),
            "Party", NameNotBlankRule.DESCRIPTOR);

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** A store of the reference domain in which Party names {@code name-not-blank}, which it has the code of. */
    private static ReferenceStore partyNamesRuleWithCode() {
        return ReferenceStore.withRules(PARTY_NAMES_RULE, ReferenceStore.rulesWith(new NameNotBlankRule()),
                ReferenceModel.declaredRules());
    }

    // ------------------------------------------------------------------ rules in Java

    @Test
    @DisplayName("Scenario: Rules become available to the schema")
    void rulesBecomeAvailableToTheSchema() {
        Store store = Stores.inMemory(StoreSettings.builder().clock(new TestClock())
                .rules(new LeiChecksumRule()).declaredRules(List.of(TotalOwnershipRule.DESCRIPTOR)).build());

        RegistrationResult result = store.schema().register(ReferenceModel.origin("1.0.0"),
                ReferenceModel.definition());

        assertThat(result).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(store.schema().availableRules()).extracting(RuleDescriptor::name, RuleDescriptor::version)
                .contains(tuple("lei-checksum", 1), tuple("total-ownership-at-most-100", 1));
        assertThat(store.schema().availableRules()).contains(LeiChecksumRule.DESCRIPTOR);
    }

    @Test
    @DisplayName("Scenario: Listing declared rules")
    void listingDeclaredRules() {
        Store store = Stores.inMemory(StoreSettings.builder().clock(new TestClock())
                .rules(new LeiChecksumRule()).declaredRules(List.of(TotalOwnershipRule.DESCRIPTOR)).build());

        assertThat(store.declaredRules()).containsExactly(TotalOwnershipRule.DESCRIPTOR);
        assertThat(store.schema().availableRules()).containsExactlyInAnyOrder(LeiChecksumRule.DESCRIPTOR,
                TotalOwnershipRule.DESCRIPTOR);
    }

    @Test
    @DisplayName("Scenario: A declared rule without code")
    void aDeclaredRuleWithoutCode() {
        List<RuleDescriptor> declared = new ArrayList<>(ReferenceModel.declaredRules());
        declared.add(NameNotBlankRule.DESCRIPTOR);
        ReferenceStore reference = ReferenceStore.withRules(PARTY_NAMES_RULE, ReferenceModel.executableRules(),
                declared);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "Alice")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo("$alice");
        assertThat(problem.message()).contains("name-not-blank");
        assertThat(reference.store.latestBasis()).as("only the schema is recorded").isEqualTo(new Basis(1));
    }

    // ------------------------------------------------------------------ rules on relationships

    private static final TemporaryId ACME_OWNS = new TemporaryId("acmeOwns");
    private static final TemporaryId GAMMA_OWNS = new TemporaryId("gammaOwns");

    @Test
    @DisplayName("Scenario: Owners holding more than 100%")
    void ownersHoldingMoreThan100Percent() {
        ReferenceStore reference = new ReferenceStore();
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        reference.replay(ReferenceStories.rd2Ownership(entity(parties, ACME), beta));

        List<Problem> problems = reference.rejectReplay(ReferenceStories.rd13GammaOwnsBeta(beta));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("total-ownership-at-most-100/over-100-percent");
        assertThat(problem.path()).isEqualTo("$gammaOwnsBeta");
        assertThat(problem.message()).as("ACME's 60% as known on 1 April, and Gamma's 50%").contains("110");
        assertThat(reference.view(on(5, 1)).entities("Organization")).as("Gamma was not created").hasSize(2);
    }

    @Test
    @DisplayName("Scenario: Shares that change later in the period")
    void sharesThatChangeLaterInThePeriod() {
        ReferenceStore reference = new ReferenceStore();
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId beta = entity(parties, BETA);
        Receipt owners = reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate(ACME_OWNS, "Owns", acme, beta, from(2, 1), values("share", 40))
                .relate(GAMMA_OWNS, "Owns", GAMMA, beta, from(4, 1), values("share", 50)).build());
        RelationshipId acmeOwns = relationship(owners, ACME_OWNS);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(acmeOwns, from(2, 1), values("share", 55)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("total-ownership-at-most-100/over-100-percent");
        assertThat(problem.path()).isEqualTo(acmeOwns.value());
        assertThat(problem.message()).contains("105").contains(on(4, 1).toString());
        assertThat(reference.view(on(5, 1)).relationship(acmeOwns).orElseThrow().values())
                .containsEntry("share", Value.of(new BigDecimal("40")));
    }

    // ------------------------------------------------------------------ rules run at commit

    @Test
    @DisplayName("Scenario: Invalid LEI check digits")
    void invalidLeiCheckDigits() {
        ReferenceStore reference = new ReferenceStore();
        reference.createParties();

        List<Problem> problems = reference.rejectReplay(rd12GammaWrongCheckDigits());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).hasValueSatisfying(code -> assertThat(code).startsWith("lei-checksum/"));
        assertThat(problem.path()).isEqualTo("$gamma.lei");
    }

    @Test
    @DisplayName("Scenario: Valid LEI")
    void validLei() {
        ReferenceStore reference = new ReferenceStore();

        reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI)).build());

        assertThat(reference.store.latestBasis()).isEqualTo(new Basis(2));
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Rule named by a supertype")
    void ruleNamedByASupertype() {
        ReferenceStore reference = partyNamesRuleWithCode();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1), values("name", "  ")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).hasValueSatisfying(code -> assertThat(code).startsWith("name-not-blank/"));
        assertThat(problem.path()).isEqualTo("$alice.name");
    }

    @Test
    @DisplayName("Scenario: Dry run runs rules")
    void dryRunRunsRules() {
        ReferenceStore reference = new ReferenceStore();
        reference.createParties();
        Basis before = reference.store.latestBasis();
        reference.clock.moveTo(rd12GammaWrongCheckDigits().recordedAt());

        DryRunResult result = reference.store.dryRun(rd12GammaWrongCheckDigits().transaction());

        assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
        Problem problem = only(((DryRunResult.Rejected) result).problems());
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("lei-checksum/invalid-check-digits");
        assertThat(reference.store.latestBasis()).as("a dry run records nothing").isEqualTo(before);
    }

    // ------------------------------------------------------------------ rule problems

    @Test
    @DisplayName("Scenario: Rule code in the problem")
    void ruleCodeInTheProblem() {
        ReferenceStore reference = new ReferenceStore();
        reference.createParties();

        List<Problem> problems = reference.rejectReplay(rd12GammaWrongCheckDigits());

        Problem problem = only(problems);
        assertThat(problem.ruleCode()).contains("lei-checksum/invalid-check-digits");
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.message()).contains(ReferenceStories.LEI_WRONG_CHECK_DIGITS);
    }

    @Test
    @DisplayName("Scenario: Failing rule")
    void failingRule() {
        TestEntityRule failing = TestEntityRule.of("always-fails", "Organization", (version, context) -> {
            throw new IllegalStateException("boom");
        });
        ReferenceStore reference = ReferenceStore.withRules(failing.namedIn(ReferenceModel.definition(),
                "Organization"), ReferenceStore.rulesWith(failing), ReferenceModel.declaredRules());
        Basis before = reference.store.latestBasis();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.message()).contains("always-fails");
        assertThat(problem.found()).contains("boom");
        assertThat(reference.store.latestBasis()).isEqualTo(before);
        assertThat(reference.store.transactions()).isEmpty();
        assertThat(reference.view(on(2, 1)).entities("Organization")).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A rule that tries to write")
    void aRuleThatTriesToWrite() {
        AtomicReference<Store> itsStore = new AtomicReference<>();
        TestEntityRule writer = TestEntityRule.of("writes-while-checking", "Organization",
                (version, context) -> itsStore.get().commit(Transaction.by("rule")
                        .create("Organization", from(1, 1), values("name", "Written by a rule")).build()));
        ReferenceStore reference = ReferenceStore.withRules(writer.namedIn(ReferenceModel.definition(),
                "Organization"), ReferenceStore.rulesWith(writer), ReferenceModel.declaredRules());
        itsStore.set(reference.store);
        Basis before = reference.store.latestBasis();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_FAILED);
        assertThat(problem.path()).isEqualTo("$acme");
        assertThat(problem.message()).contains("writes-while-checking");
        assertThat(reference.store.latestBasis()).isEqualTo(before);
        assertThat(reference.store.transactions()).isEmpty();
        assertThat(reference.view(on(2, 1)).entities("Organization")).isEmpty();
    }

    // ------------------------------------------------------------------ rules run again when what they read changes

    /** The reference store in which Employs names employer-holds-account, with RD-1, RD-5 and RD-3 recorded. */
    private record EmployerAtWork(ReferenceStore reference, EntityId acme, EntityId beta, EntityId a1,
            RelationshipId heldByA1, RelationshipId employs) {

        static EmployerAtWork start() {
            ReferenceStore reference = ReferenceStore.withEmployerHoldsAccount();
            Receipt accounts = reference.createAccounts();
            Receipt employment = reference.replay(ReferenceStories.rd3Employment(
                    entity(reference.parties, BETA), entity(reference.parties, ALICE)));
            return new EmployerAtWork(reference, entity(reference.parties, ACME), entity(reference.parties, BETA),
                    entity(accounts, A1), relationship(accounts, HELD_BY_A1),
                    relationship(employment, ReferenceStories.BETA_EMPLOYS_ALICE));
        }
    }

    @Test
    @DisplayName("Scenario: Ending a relationship that a rule navigated")
    void endingARelationshipThatARuleNavigated() {
        EmployerAtWork work = EmployerAtWork.start();
        work.reference().recordedOn(on(5, 20));

        List<Problem> problems = work.reference().reject(Transaction.by("importer")
                .endDate(work.heldByA1(), on(6, 1))
                .relate(HELD_BY_A1_MOVED, "HeldBy", work.a1(), work.acme(), from(6, 1), values())
                .build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("employer-holds-account/no-account");
        assertThat(problem.path()).as("Beta's Employs of Alice, which the transaction does not name")
                .isEqualTo(work.employs().value());
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).isEqualTo("The transaction changes "
                + "what the rule read when it last checked " + work.employs().value() + "."));
        assertThat(work.reference().view(on(6, 1)).navigate(work.beta(), "holds")).as("nothing was moved")
                .hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Changing values that a rule read")
    void changingValuesThatARuleRead() {
        ReferenceStore reference = ReferenceStore.withControllingOwnerHasLei();
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(acme, entity(parties, BETA)));
        RelationshipId acmeOwns = relationship(ownership, ReferenceStories.ACME_OWNS_BETA);
        reference.recordedOn(on(5, 20));

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(acme, from(6, 1), values(), Set.of("lei"), ConflictCheck.none()).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("controlling-owner-has-lei/no-lei");
        assertThat(problem.path()).isEqualTo(acmeOwns.value());
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint)
                .contains("The transaction changes what the rule read"));
        assertThat(reference.view(on(7, 1)).entity(acme).orElseThrow().values()).containsKey("lei");
    }

    @Test
    @DisplayName("Scenario: One problem from a rule")
    void oneProblemFromARule() {
        ReferenceStore reference = new ReferenceStore();
        Receipt parties = reference.createParties();
        EntityId beta = entity(parties, BETA);
        Receipt ownership = reference.replay(ReferenceStories.rd2Ownership(entity(parties, ACME), beta));
        RelationshipId acmeOwns = relationship(ownership, ReferenceStories.ACME_OWNS_BETA);

        List<Problem> problems = reference.rejectReplay(ReferenceStories.rd13GammaOwnsBeta(beta));

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
        assertThat(problem.ruleCode()).contains("total-ownership-at-most-100/over-100-percent");
        assertThat(problem.path()).as("Gamma's Owns, not ACME's").isEqualTo("$gammaOwnsBeta");
        assertThat(problem.hint()).as("the rule ran for what the transaction writes").isEmpty();
        assertThat(problems).extracting(Problem::path).doesNotContain(acmeOwns.value());
    }

    @Test
    @DisplayName("Scenario: No re-checks after other problems")
    void noRechecksAfterOtherProblems() {
        EmployerAtWork work = EmployerAtWork.start();
        work.reference().recordedOn(on(5, 20));

        List<Problem> problems = work.reference().reject(Transaction.by("importer")
                .endDate(work.heldByA1(), on(6, 1)).build());

        Problem problem = only(problems);
        assertThat(problem.code()).isEqualTo(ProblemCode.TOO_FEW_RELATIONSHIPS);
        assertThat(problem.path()).as("A-1 and HeldBy").isEqualTo(work.a1().value() + "[HeldBy]");
        assertThat(problems).as("no problem from the rule").noneMatch(found -> found.ruleCode().isPresent());
    }
}
