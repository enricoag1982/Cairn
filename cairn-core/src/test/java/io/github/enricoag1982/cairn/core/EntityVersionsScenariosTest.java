package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd5BalanceCorrection;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd5BalancePatch;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd7Rename;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd9AcmeLei;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.rd9Gamma;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code entity-versions} spec, one test each. */
class EntityVersionsScenariosTest {

    private final ReferenceStore reference = new ReferenceStore();

    private EntityState state(EntityId id, java.time.Instant effectiveAt) {
        return reference.view(effectiveAt).entity(id).orElseThrow();
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** Account A-1 and A-2 from 1 January (RD-5); returns A-1's ID. */
    private EntityId accounts() {
        Receipt receipt = reference.createAccounts();
        return (EntityId) receipt.temporaryIds().get(A1);
    }

    private EntityId acme() {
        return (EntityId) reference.createParties().temporaryIds().get(ACME);
    }

    // ------------------------------------------------------------------ creating entities

    @Test
    @DisplayName("Scenario: Creating an organization")
    void creatingAnOrganization() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());
        EntityId acme = (EntityId) receipt.temporaryIds().get(ACME);

        assertThat(receipt.transactionTime()).isBetween(on(1, 5), on(1, 6));
        EntityState state = state(acme, on(2, 1));
        assertThat(state.entityType()).isEqualTo("Organization");
        assertThat(state.values()).containsOnly(java.util.Map.entry("name", Value.of("ACME")));
        assertThat(reference.view(on(2025, 12, 31)).entity(acme)).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Creating an entity of an abstract type")
    void creatingAnEntityOfAnAbstractType() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Party", from(1, 1), values("name", "ACME")).build());

        assertProblem(only(problems), ProblemCode.ABSTRACT_ENTITY_TYPE, "$acme");
    }

    @Test
    @DisplayName("Scenario: Misspelled entity type")
    void misspelledEntityType() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organisation", from(1, 1), values("name", "ACME")).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.UNKNOWN_ENTITY_TYPE, "$acme");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Organization"));
        assertThat(problem.found()).contains("Organisation");
    }

    // ------------------------------------------------------------------ attribute values

    @Test
    @DisplayName("Scenario: Value of the wrong type")
    void valueOfTheWrongType() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", Value.of(42))).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.VALUE_TYPE_MISMATCH, "$acme.name");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("string"));
        assertThat(problem.found()).hasValueSatisfying(found -> assertThat(found).contains("int"));
    }

    @Test
    @DisplayName("Scenario: Value that breaks a pattern")
    void valueThatBreaksAPattern() {
        EntityId beta = (EntityId) reference.createParties().temporaryIds().get(BETA);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(A1, "Account", from(1, 1),
                        values("number", "A-1", "currency", "eur", "balance", money("1000")))
                .relate("HeldBy", A1, beta, from(1, 1), values()).build());

        assertProblem(only(problems), ProblemCode.VALUE_PATTERN_MISMATCH, "$a1.currency");
    }

    @Test
    @DisplayName("Scenario: Unknown attribute")
    void unknownAttribute() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("nmae", "ACME")).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.UNKNOWN_ATTRIBUTE, "$acme.nmae");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("name"));
    }

    @Test
    @DisplayName("Scenario: Problem about an entity without a temporary ID")
    void problemAboutAnEntityWithoutATemporaryId() {
        Transaction invalid = Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create("Organization", from(1, 1), values())
                .build();

        DryRunResult dry = reference.store.dryRun(invalid);
        List<Problem> committing = reference.reject(invalid);

        assertProblem(only(committing), ProblemCode.MISSING_REQUIRED_VALUE, "@write.1.name");
        assertThat(dry).isInstanceOf(DryRunResult.Rejected.class);
        assertProblem(only(((DryRunResult.Rejected) dry).problems()), ProblemCode.MISSING_REQUIRED_VALUE,
                "@write.1.name");
    }

    @Test
    @DisplayName("Scenario: Missing required attribute")
    void missingRequiredAttribute() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values()).build());

        assertProblem(only(problems), ProblemCode.MISSING_REQUIRED_VALUE, "$acme.name");
    }

    @Test
    @DisplayName("Scenario: Writing a deprecated attribute")
    void writingADeprecatedAttribute() {
        EntityId alice = (EntityId) reference.createParties().temporaryIds().get(ALICE);
        var deprecated = Definitions.changeEntityType(ReferenceModel.definition(), "Person", person ->
                Definitions.changeAttribute(person, "birthDate", old ->
                        AttributeDefinition.builder("birthDate", ValueType.DATE).deprecated().build()));
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), deprecated))
                .isInstanceOf(RegistrationResult.Applied.class);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(alice, from(1, 1), values("birthDate", LocalDate.of(1990, 5, 17))).build());

        assertProblem(only(problems), ProblemCode.DEPRECATED_ATTRIBUTE, alice.value() + ".birthDate");
    }

    // ------------------------------------------------------------------ patching

    @Test
    @DisplayName("Scenario: Patching a balance")
    void patchingABalance() {
        EntityId a1 = accounts();

        reference.replay(rd5BalancePatch(a1));

        EntityState march31 = state(a1, on(3, 31));
        assertThat(march31.values()).containsEntry("balance", Value.of(money("1500")))
                .containsEntry("currency", Value.of("EUR")).containsEntry("number", Value.of("A-1"));
        assertThat(state(a1, on(3, 14)).values()).containsEntry("balance", Value.of(money("1000")));
    }

    @Test
    @DisplayName("Scenario: Future-dated change")
    void futureDatedChange() {
        EntityId acme = acme();

        reference.replay(rd7Rename(acme));

        assertThat(state(acme, on(2026, 12, 31)).values()).containsEntry("name", Value.of("ACME"));
        assertThat(state(acme, on(2027, 1, 1)).values()).containsEntry("name", Value.of("ACME Holdings"));
    }

    @Test
    @DisplayName("Scenario: Patching before an entity exists")
    void patchingBeforeAnEntityExists() {
        EntityId acme = acme();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(acme, EffectivePeriod.from(on(2025, 12, 1)), values("name", "ACME Holdings")).build());

        assertProblem(only(problems), ProblemCode.ENTITY_NOT_EFFECTIVE, acme.value());
    }

    @Test
    @DisplayName("Scenario: Restoring an earlier value")
    void restoringAnEarlierValue() {
        Receipt created = reference.createParties();
        EntityId acme = (EntityId) created.temporaryIds().get(ACME);
        reference.commit(Transaction.by("importer").patch(acme, from(3, 1), values("name", "ACME Group")).build());
        Receipt restored = reference.commit(Transaction.by("importer")
                .patch(acme, from(3, 1), values("name", "ACME")).build());

        List<EntityState> timeline = reference.view(on(4, 1)).timeline(acme);

        assertThat(timeline).hasSize(2);
        assertThat(timeline).extracting(EntityState::values).containsExactly(
                Map.of("name", Value.of("ACME")), Map.of("name", Value.of("ACME")));
        assertThat(timeline).extracting(EntityState::period).containsExactly(
                EffectivePeriod.between(on(1, 1), on(3, 1)), EffectivePeriod.from(on(3, 1)));
        assertThat(timeline).extracting(EntityState::recordedBasis).containsExactly(created.basis(),
                restored.basis());
        assertThat(timeline).extracting(EntityState::recordedAt).containsExactly(created.transactionTime(),
                restored.transactionTime());
    }

    // ------------------------------------------------------------------ replacing

    @Test
    @DisplayName("Scenario: Replacing a person's state")
    void replacingAPersonsState() {
        Receipt created = reference.commit(Transaction.by("importer")
                .create(ALICE, "Person", from(1, 1),
                        values("name", "Alice", "birthDate", LocalDate.of(1990, 5, 17))).build());
        EntityId alice = (EntityId) created.temporaryIds().get(ALICE);

        reference.commit(Transaction.by("importer").replace(alice, from(6, 1), values("name", "Alice")).build());

        assertThat(state(alice, on(6, 1)).values()).containsOnlyKeys("name");
        assertThat(state(alice, on(5, 31)).values()).containsKey("birthDate");
    }

    @Test
    @DisplayName("Scenario: Replacing an account's state")
    void replacingAnAccountsState() {
        EntityId a1 = accounts();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .replace(a1, from(6, 1), values("number", "A-1", "balance", money("1000"))).build());

        assertProblem(only(problems), ProblemCode.MISSING_REQUIRED_VALUE, a1.value() + ".currency");
    }

    @Test
    @DisplayName("Scenario: A replace that leaves out a withheld balance")
    void aReplaceThatLeavesOutAWithheldBalance() {
        ReferenceStore classified = ReferenceStore.with(
                Definitions.withClassification(ReferenceModel.definition(), "Account", "balance", "finance"));
        EntityId a1 = (EntityId) classified.createAccounts().temporaryIds().get(A1);
        classified.replay(rd5BalancePatch(a1));
        classified.replay(rd5BalanceCorrection(a1));
        classified.recordedOn(on(5, 2));

        classified.commit(Transaction.by("importer").clearance(Clearance.none())
                .replace(a1, from(5, 1), values("number", "A-1", "currency", "EUR")).build());

        EntityState unrestricted = classified.view(on(5, 1)).entity(a1).orElseThrow();
        assertThat(unrestricted.values()).containsEntry("balance", Value.of(money("1200")))
                .containsEntry("number", Value.of("A-1")).containsEntry("currency", Value.of("EUR"));
        assertThat(classified.view(on(4, 30)).entity(a1).orElseThrow().values())
                .containsEntry("balance", Value.of(money("1200")));
    }

    // ------------------------------------------------------------------ end-dating

    @Test
    @DisplayName("Scenario: Ending an organization")
    void endingAnOrganization() {
        EntityId beta = (EntityId) reference.createParties().temporaryIds().get(BETA);

        reference.commit(Transaction.by("importer").endDate(beta, on(6, 1)).build());

        assertThat(reference.view(on(5, 31)).entity(beta)).isPresent();
        assertThat(reference.view(on(6, 1)).entity(beta)).isEmpty();
        assertThat(reference.view(on(6, 1)).entities("Organization")).extracting(EntityState::id)
                .doesNotContain(beta);
    }

    @Test
    @DisplayName("Scenario: Ending an entity before it starts")
    void endingAnEntityBeforeItStarts() {
        EntityId beta = (EntityId) reference.createParties().temporaryIds().get(BETA);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .endDate(beta, on(2025, 12, 1)).build());

        assertProblem(only(problems), ProblemCode.ENTITY_NOT_EFFECTIVE, beta.value());
        assertThat(reference.view(on(2, 1)).entity(beta)).isPresent();
    }

    // ------------------------------------------------------------------ corrections

    @Test
    @DisplayName("Scenario: Correcting a balance")
    void correctingABalance() {
        EntityId a1 = accounts();
        reference.replay(rd5BalancePatch(a1));
        reference.replay(rd5BalanceCorrection(a1));

        var knownApril5 = reference.store.view(Coordinates.effectiveAt(on(3, 31)).knownAt(on(4, 5)));
        var knownNow = reference.view(on(3, 31));

        assertThat(knownApril5.entity(a1).orElseThrow().values()).containsEntry("balance", Value.of(money("1500")));
        assertThat(knownNow.entity(a1).orElseThrow().values()).containsEntry("balance", Value.of(money("1200")));
    }

    // ------------------------------------------------------------------ uniqueness

    @Test
    @DisplayName("Scenario: Duplicate LEI")
    void duplicateLei() {
        reference.replay(rd9AcmeLei(acme()));

        List<Problem> problems = reference.rejectReplay(rd9Gamma());

        assertProblem(only(problems), ProblemCode.UNIQUE_VALUE_TAKEN, "$gamma.lei");
    }

    @Test
    @DisplayName("Scenario: Reusing a value after it ends")
    void reusingAValueAfterItEnds() {
        EntityId acme = acme();
        reference.replay(rd9AcmeLei(acme));
        reference.commit(Transaction.by("importer")
                .patch(acme, from(3, 1), values(), Set.of("lei"), ConflictCheck.none()).build());

        Receipt receipt = reference.replay(rd9Gamma());

        assertThat(receipt.created()).hasSize(1);
        assertThat(state(acme, on(2, 1)).values()).containsEntry("lei", Value.of(LEI));
        assertThat(state(acme, on(3, 1)).values()).doesNotContainKey("lei");
    }

    // ------------------------------------------------------------------ conflict checks

    @Test
    @DisplayName("Scenario: Writers that change different attributes")
    void writersThatChangeDifferentAttributes() {
        EntityId a1 = accounts();
        var readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("first").patch(a1, from(3, 15), values("balance", money("1500"))).build());

        Receipt second = reference.commit(Transaction.by("second")
                .patch(a1, from(3, 15), values("currency", "USD"), ConflictCheck.perAttribute(readAt)).build());

        assertThat(second.basis()).isGreaterThan(readAt);
        assertThat(state(a1, on(3, 31)).values()).containsEntry("balance", Value.of(money("1500")))
                .containsEntry("currency", Value.of("USD"));
    }

    @Test
    @DisplayName("Scenario: Writers that change the same attribute")
    void writersThatChangeTheSameAttribute() {
        EntityId a1 = accounts();
        var readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("first").patch(a1, from(3, 15), values("balance", money("1500"))).build());

        List<Problem> problems = reference.reject(Transaction.by("second")
                .patch(a1, from(3, 15), values("balance", money("1700")), ConflictCheck.perAttribute(readAt))
                .build());

        assertProblem(only(problems), ProblemCode.CONFLICT, a1.value() + ".balance");
        assertThat(state(a1, on(3, 31)).values()).containsEntry("balance", Value.of(money("1500")));
    }
}
