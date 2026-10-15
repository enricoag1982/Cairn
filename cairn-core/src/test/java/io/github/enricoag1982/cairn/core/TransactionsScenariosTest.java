package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI_WRONG_CHECK_DIGITS;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.KeyResolution;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.TransactionRecord;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scenarios of the {@code transactions} spec, one test each. */
class TransactionsScenariosTest {

    private static final Pattern UUID_V7 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final ReferenceStore reference = new ReferenceStore();
    private final Store store = reference.store;

    private static Transaction createsAcme() {
        return Transaction.by("importer").create(ACME, "Organization", from(1, 1), values("name", "ACME")).build();
    }

    // ------------------------------------------------------------------ committing a transaction

    @Test
    @DisplayName("Scenario: Creating several entities in one transaction")
    void creatingSeveralEntitiesInOneTransaction() {
        Receipt receipt = reference.createParties();

        assertThat(receipt.basis()).isEqualTo(new Basis(2));
        assertThat(receipt.transactionTime()).isBetween(on(1, 5), on(1, 6));
        assertThat(receipt.created()).hasSize(3).doesNotHaveDuplicates();
        assertThat(receipt.temporaryIds()).containsOnlyKeys(ACME, BETA, ALICE);
        assertThat(receipt.temporaryIds().values()).containsExactlyElementsOf(receipt.created());
        assertThat(reference.view(on(2, 1)).entities("Party")).hasSize(3);
    }

    @Test
    @DisplayName("Scenario: One invalid write rejects the transaction")
    void oneInvalidWriteRejectsTheTransaction() {
        Basis before = store.latestBasis();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .create(BETA, "Organization", from(1, 1), values())
                .build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.MISSING_REQUIRED_VALUE, "$beta.name"));
        assertThat(reference.view(on(2, 1)).entities("Organization")).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(before);
        assertThat(store.transactions()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Data and schema share one sequence")
    void dataAndSchemaShareOneSequence() {
        Basis schemaBasis = store.latestBasis();
        var schemaTime = store.schema().history().getLast().transactionTime();

        Receipt data = reference.createParties();
        RegistrationResult.Applied later = (RegistrationResult.Applied) store.schema().register(
                new Origin("extra", "1"), SchemaDefinition.builder().entityType("Extra").build());

        assertThat(data.basis()).isGreaterThan(schemaBasis);
        assertThat(data.transactionTime()).isAfter(schemaTime);
        assertThat(later.basis()).isGreaterThan(data.basis());
        assertThat(later.transactionTime()).isAfter(data.transactionTime());
    }

    // ------------------------------------------------------------------ who and why

    @Test
    @DisplayName("Scenario: An agent acting for a person")
    void anAgentActingForAPerson() {
        Receipt receipt = reference.commit(Transaction.by("agent:onboarding").onBehalfOf("alice")
                .reason("New customer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        assertThat(store.transactions()).containsExactly(new TransactionRecord(receipt.basis(),
                receipt.transactionTime(), "agent:onboarding", Optional.of("alice"), Optional.of("New customer")));
        assertThat(receipt.principal()).isEqualTo("agent:onboarding");
        assertThat(receipt.onBehalfOf()).contains("alice");
        assertThat(receipt.reason()).contains("New customer");
    }

    @Test
    @DisplayName("Scenario: Missing principal")
    void missingPrincipal() {
        List<Problem> problems = reference.reject(Transaction.by("  ")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.MISSING_PRINCIPAL, "@transaction"));
        assertThat(store.transactions()).isEmpty();
    }

    // ------------------------------------------------------------------ temporary IDs

    @Test
    @DisplayName("Scenario: Referring to an entity created in the same transaction")
    void referringToAnEntityCreatedInTheSameTransaction() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .patch(ACME, from(3, 1), values("name", "ACME Holdings"))
                .build());

        EntityId acme = (EntityId) receipt.temporaryIds().get(ACME);
        assertThat(receipt.created()).containsExactly(acme);
        assertThat(reference.view(on(4, 1)).entity(acme)).get()
                .satisfies(state -> assertThat(state.values()).containsEntry("name", Value.of("ACME Holdings")));
        assertThat(reference.view(on(2, 1)).entity(acme)).get()
                .satisfies(state -> assertThat(state.values()).containsEntry("name", Value.of("ACME")));
    }

    @Test
    @DisplayName("Scenario: Unknown temporary ID")
    void unknownTemporaryId() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(BETA, from(3, 1), values("name", "Beta Ltd")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.UNKNOWN_TEMPORARY_ID, "$beta"));
    }

    @Test
    @DisplayName("Scenario: Temporary ID used before it is created")
    void temporaryIdUsedBeforeItIsCreated() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(ACME, from(3, 1), values("name", "ACME Holdings"))
                .create(ACME, "Organization", from(1, 1), values("name", "ACME"))
                .build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.UNKNOWN_TEMPORARY_ID, "$acme"));
        assertThat(store.transactions()).isEmpty();
    }

    // ------------------------------------------------------------------ entity IDs

    @Test
    @DisplayName("Scenario: Default IDs are UUIDv7")
    void defaultIdsAreUuidV7() {
        Store defaults = Stores.inMemory();
        defaults.schema().register(new Origin("things", "1"), SchemaDefinition.builder()
                .entityType("Thing", type -> type.attribute("name", ValueType.STRING)).build());

        CommitResult result = defaults.commit(Transaction.by("tester")
                .create("Thing", from(1, 1), values("name", "A thing")).build());

        Receipt receipt = ((CommitResult.Committed) result).receipt();
        assertThat(receipt.created()).singleElement().satisfies(created ->
                assertThat(created.value()).matches(UUID_V7));
    }

    @Test
    @DisplayName("Scenario: Supplying an ID that exists")
    void supplyingAnIdThatExists() {
        EntityId supplied = id(77);
        reference.commit(Transaction.by("importer")
                .create(supplied, "Organization", from(1, 1), values("name", "ACME")).build());

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(supplied, "Organization", from(1, 1), values("name", "Beta")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.ENTITY_EXISTS, supplied.value()));
    }

    @Test
    @DisplayName("Scenario: Deterministic IDs")
    void deterministicIds() {
        ReferenceStore other = new ReferenceStore();

        Receipt first = reference.createParties();
        Receipt second = other.createParties();

        assertThat(first.created()).containsExactly(id(1), id(2), id(3));
        assertThat(second.created()).isEqualTo(first.created());
        assertThat(second.temporaryIds()).isEqualTo(first.temporaryIds());
    }

    // ------------------------------------------------------------------ dry runs

    @Test
    @DisplayName("Scenario: Dry run of a valid transaction")
    void dryRunOfAValidTransaction() {
        Basis before = store.latestBasis();

        DryRunResult result = store.dryRun(createsAcme());

        assertThat(result).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(reference.view(on(2, 1)).entities("Organization")).isEmpty();
        assertThat(store.latestBasis()).isEqualTo(before);
        assertThat(store.transactions()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Dry run of an invalid transaction")
    void dryRunOfAnInvalidTransaction() {
        Transaction invalid = Transaction.by("importer")
                .create(ACME, "Organization", from(1, 1), values()).build();

        DryRunResult dry = store.dryRun(invalid);
        List<Problem> committing = reference.reject(invalid);

        assertThat(dry).isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) dry).problems()).isEqualTo(committing)
                .singleElement().satisfies(problem -> assertThat(problem.code())
                        .isEqualTo(ProblemCode.MISSING_REQUIRED_VALUE));
    }

    // ------------------------------------------------------------------ idempotency keys

    @Test
    @DisplayName("Scenario: Retrying a committed transaction")
    void retryingACommittedTransaction() {
        Transaction onboard = Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build();
        Receipt first = reference.commit(onboard);
        reference.clock.advance(Duration.ofMinutes(5));

        CommitResult second = store.commit(onboard);

        assertThat(second).isEqualTo(new CommitResult.Committed(first));
        assertThat(store.transactions()).hasSize(1);
        assertThat(store.latestBasis()).isEqualTo(first.basis());
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(1);
        assertThat(first.idempotencyKey()).contains("onboard-acme");
    }

    @Test
    @DisplayName("Scenario: Reusing a key for other writes")
    void reusingAKeyForOtherWrites() {
        reference.commit(Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME")).build());

        List<Problem> problems = reference.reject(Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(BETA, "Organization", from(1, 1), values("name", "Beta")).build());

        assertThat(problems).singleElement().satisfies(problem -> assertProblem(problem,
                ProblemCode.IDEMPOTENCY_KEY_REUSED, "@transaction"));
        assertThat(store.transactions()).hasSize(1);
    }

    @Test
    @DisplayName("Scenario: Retrying writes that are now invalid")
    void retryingWritesThatAreNowInvalid() {
        Transaction onboard = Transaction.by("agent:onboarding").idempotencyKey("onboard-acme")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI)).build();
        Receipt first = reference.commit(onboard);
        EntityId acme = (EntityId) first.temporaryIds().get(ACME);
        reference.commit(Transaction.by("importer")
                .patch(acme, from(1, 1), values(), Set.of("lei"), ConflictCheck.none()).build());
        reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(3, 1), values("name", "Gamma", "lei", LEI)).build());
        Basis before = store.latestBasis();
        int recorded = store.transactions().size();
        DryRunResult withoutKey = store.dryRun(Transaction.by("agent:onboarding")
                .create(ACME, "Organization", from(1, 1), values("name", "ACME", "lei", LEI)).build());
        assertThat(withoutKey).as("the same writes, without the key, are invalid now")
                .isInstanceOf(DryRunResult.Rejected.class);
        assertThat(((DryRunResult.Rejected) withoutKey).problems()).singleElement().satisfies(problem ->
                assertProblem(problem, ProblemCode.UNIQUE_VALUE_TAKEN, "$acme.lei"));

        CommitResult retry = store.commit(onboard);

        assertThat(retry).isEqualTo(new CommitResult.Committed(first));
        assertThat(store.dryRun(onboard)).isInstanceOf(DryRunResult.WouldCommit.class);
        assertThat(store.latestBasis()).isEqualTo(before);
        assertThat(store.transactions()).hasSize(recorded);
    }

    @Test
    @DisplayName("Scenario: Problems of re-checks come last")
    void problemsOfRechecksComeLast() {
        ReferenceStore owners = ReferenceStore.withControllingOwnerHasLei();
        Receipt parties = owners.createParties();
        EntityId acme = entity(parties, ACME);
        owners.replay(ReferenceStories.rd9AcmeLei(acme));
        Receipt ownership = owners.replay(ReferenceStories.rd2Ownership(acme, entity(parties, BETA)));
        RelationshipId acmeOwns = relationship(ownership, ReferenceStories.ACME_OWNS_BETA);
        owners.recordedOn(on(5, 20));

        List<Problem> problems = owners.reject(Transaction.by("importer")
                .patch(acme, from(6, 1), values(), Set.of("lei"), ConflictCheck.none())
                .create(GAMMA, "Organization", from(6, 1), values("name", "Gamma", "lei", LEI_WRONG_CHECK_DIGITS))
                .build());

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0).ruleCode()).contains("lei-checksum/invalid-check-digits");
        assertThat(problems.get(0).path()).as("Gamma, though the write of ACME comes first").isEqualTo("$gamma.lei");
        assertThat(problems.get(1).ruleCode()).contains("controlling-owner-has-lei/no-lei");
        assertThat(problems.get(1).path()).as("the re-check").isEqualTo(acmeOwns.value());
    }

    // ------------------------------------------------------------------ unique keys

    private static UniqueKey key(String entityType, String attribute, String value) {
        return new UniqueKey(entityType, attribute, Value.of(value));
    }

    @Test
    @DisplayName("Scenario: Patching an account by its number")
    void patchingAnAccountByItsNumber() {
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, ReferenceStories.A1);

        Receipt receipt = reference.commit(Transaction.by("importer")
                .patch(key("Account", "number", "A-1"), from(3, 15), values("balance", money("1500"))).build());

        assertThat(receipt.created()).isEmpty();
        assertThat(reference.view(on(3, 31)).entity(a1).orElseThrow().values())
                .containsEntry("balance", Value.of(money("1500")));
        assertThat(reference.view(on(3, 14)).entity(a1).orElseThrow().values())
                .containsEntry("balance", Value.of(money("1000")));
        assertThat(receipt.keys()).as("the key of the write at position 0, with A-1's ID")
                .containsExactly(new KeyResolution(0, key("Account", "number", "A-1"), a1));
    }

    @Test
    @DisplayName("Scenario: Relating by a key")
    void relatingByAKey() {
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        EntityId alice = entity(parties, ALICE);
        reference.replay(ReferenceStories.rd9AcmeLei(acme));
        reference.recordedOn(on(3, 1));

        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate("Employs", key("Organization", "lei", LEI), alice, from(3, 1), values("title", "Analyst"))
                .build());

        assertThat(receipt.created()).singleElement().isInstanceOf(RelationshipId.class);
        assertThat(reference.view(on(3, 1)).navigate(acme, "Employs")).singleElement().satisfies(link -> {
            assertThat(link.entity().id()).isEqualTo(alice);
            assertThat(link.relationship().source()).isEqualTo(acme);
        });
        assertThat(reference.view(on(2, 28)).navigate(acme, "Employs")).isEmpty();
    }

    /** ACME has the LEI until 1 March, and Gamma has it from 1 March. Returns the IDs of ACME and Gamma. */
    private List<EntityId> leiMovesFromAcmeToGamma() {
        EntityId acme = entity(reference.createParties(), ACME);
        reference.commit(Transaction.by("importer")
                .patch(acme, EffectivePeriod.between(on(1, 1), on(3, 1)), values("lei", LEI)).build());
        reference.replay(ReferenceStories.rd9Gamma());
        EntityId gamma = reference.view(on(3, 1)).entities("Organization").stream()
                .filter(state -> state.values().get("name").equals(Value.of("Gamma"))).findFirst().orElseThrow().id();
        return List.of(acme, gamma);
    }

    @Test
    @DisplayName("Scenario: A key whose value moved")
    void aKeyWhoseValueMoved() {
        List<EntityId> moved = leiMovesFromAcmeToGamma();
        EntityId acme = moved.get(0);
        EntityId gamma = moved.get(1);
        reference.recordedOn(on(4, 1));

        Receipt receipt = reference.commit(Transaction.by("importer")
                .patch(key("Organization", "lei", LEI), from(4, 1), values("name", "Gamma Holdings")).build());

        assertThat(receipt.created()).isEmpty();
        assertThat(reference.view(on(4, 1)).entity(gamma).orElseThrow().values())
                .containsEntry("name", Value.of("Gamma Holdings"));
        assertThat(reference.view(on(4, 1)).entity(acme).orElseThrow().values()).containsEntry("name", Value.of("ACME"));
        assertThat(reference.view(on(3, 1)).entity(gamma).orElseThrow().values())
                .as("before the patch").containsEntry("name", Value.of("Gamma"));
    }

    @Test
    @DisplayName("Scenario: A key that names no entity")
    void aKeyThatNamesNoEntity() {
        reference.createAccounts();
        Basis before = reference.store.latestBasis();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(key("Account", "number", "A-9"), from(3, 15), values("balance", money("1500"))).build());

        assertThat(problems).singleElement().satisfies(problem -> {
            assertProblem(problem, ProblemCode.UNIQUE_KEY_NOT_FOUND, "@write.0");
            assertThat(problem.message()).contains("Account(number: A-9)");
        });
        assertThat(reference.store.latestBasis()).isEqualTo(before);
    }

    @Test
    @DisplayName("Scenario: A key on an attribute that is not unique")
    void aKeyOnAnAttributeThatIsNotUnique() {
        reference.createAccounts();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(key("Account", "currency", "EUR"), from(3, 15), values("balance", money("1500"))).build());

        assertThat(problems).singleElement().satisfies(problem -> {
            assertProblem(problem, ProblemCode.INVALID_UNIQUE_KEY, "@write.0");
            assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("number"));
            assertThat(problem.message()).contains("Account(currency: EUR)", "currency");
        });
    }
}
