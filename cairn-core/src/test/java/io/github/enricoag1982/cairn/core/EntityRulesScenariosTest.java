package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd12GammaWrongCheckDigits;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.NameNotBlankRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.ArrayList;
import java.util.List;
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
        return ReferenceStore.withRules(PARTY_NAMES_RULE, List.of(new LeiChecksumRule(), new NameNotBlankRule()),
                ReferenceModel.declaredRules());
    }

    // ------------------------------------------------------------------ rules in Java

    @Test
    @DisplayName("Scenario: Rules become available to the schema")
    void rulesBecomeAvailableToTheSchema() {
        Store store = Stores.inMemory(StoreSettings.builder().clock(new TestClock())
                .rules(new LeiChecksumRule()).declaredRules(ReferenceModel.declaredRules()).build());

        RegistrationResult result = store.schema().register(ReferenceModel.origin("1.0.0"),
                ReferenceModel.definition());

        assertThat(result).isInstanceOf(RegistrationResult.Applied.class);
        assertThat(store.schema().availableRules()).extracting(RuleDescriptor::name, RuleDescriptor::version)
                .contains(tuple("lei-checksum", 1), tuple("total-ownership-at-most-100", 1));
        assertThat(store.schema().availableRules()).contains(LeiChecksumRule.DESCRIPTOR);
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
                "Organization"), List.of(new LeiChecksumRule(), failing), ReferenceModel.declaredRules());
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
                "Organization"), List.of(new LeiChecksumRule(), writer), ReferenceModel.declaredRules());
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
}
