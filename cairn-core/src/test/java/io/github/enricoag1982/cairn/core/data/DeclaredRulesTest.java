package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.NameNotBlankRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules that a store declares without having their code (design D2 of {@code add-entity-rules}): any write of an entity
 * whose type, or one of whose supertypes, names such a rule is rejected, whatever the write changes.
 *
 * <p>Alice, a Person, exists before Party names {@code name-not-blank}, which the store declares without its code. A
 * schema cannot add a rule to a type that has entities (that is a breaking change), so no store gets there by itself
 * in memory; it is what a store would find if it were created again without the code of a rule that it had when Alice
 * was recorded. The tests therefore put Alice into the store's data by hand, and prepare transactions the way a store
 * does.
 */
class DeclaredRulesTest {

    private static final EntityId ALICE = new EntityId("00000000-0000-7000-8000-000000000003");
    private static final TemporaryId HOME = new TemporaryId("home");
    private static final Instant NEXT_COMMIT_AT = Instant.parse("2026-03-16T09:00:00Z");

    private final CommitSequence sequence = new CommitSequence(new TestClock());
    private final Map<EntityId, StoredEntity> stored = new HashMap<>();
    private Schema schema;

    @BeforeEach
    void aSchemaThatNamesTheDeclaredRuleAndAnEntityThatAlreadyExists() {
        List<RuleDescriptor> available = new ArrayList<>(ReferenceModel.rules());
        available.add(NameNotBlankRule.DESCRIPTOR);
        InMemorySchemaRegistry registry = new InMemorySchemaRegistry(sequence, available);
        SchemaDefinition model = Definitions.changeEntityType(ReferenceModel.definition(), "Party", party ->
                new EntityTypeDefinition(party.name(), party.isAbstract(), party.supertype(), party.traits(),
                        party.attributes(), party.aliases(), party.deprecated(), party.description(),
                        List.of(new RuleReference(NameNotBlankRule.DESCRIPTOR.name(),
                                NameNotBlankRule.DESCRIPTOR.version()))));
        assertThat(registry.register(ReferenceModel.origin("1.0.0"), model))
                .isInstanceOf(RegistrationResult.Applied.class);
        schema = registry.current();

        EntityType person = schema.entityType("Person").orElseThrow();
        StoredEntity alice = new StoredEntity(ALICE, person.id());
        alice.append(new Basis(2), Timeline.created(on(1, 1), Segment.OPEN,
                Map.of(person.attribute("name").orElseThrow().id(), Value.of("Alice")),
                Instant.parse("2026-01-05T09:00:00Z"), new Basis(2)));
        stored.put(ALICE, alice);
    }

    /** Prepares a transaction as the store would, as the third commit, with only the code of the given rules. */
    private List<Problem> problemsOf(Transaction transaction) {
        return sequence.underWriterLock(() -> new Preparation(transaction, new SchemaIndex(schema), stored,
                new HashMap<>(), IdGenerators.deterministic(), new CommitSequence.Commit(new Basis(3), NEXT_COMMIT_AT),
                new ValueChecks(), new Rules(List.of(new LeiChecksumRule())), sequence).run().problems());
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static void assertUnavailable(Problem problem) {
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo(ALICE.value());
        assertThat(problem.message()).contains("name-not-blank");
    }

    @Test
    @DisplayName("Scenario: Ending an entity whose rule has no code")
    void endingAnEntityWhoseRuleHasNoCode() {
        List<Problem> problems = problemsOf(Transaction.by("importer").endDate(ALICE, on(6, 1)).build());

        assertUnavailable(only(problems));
    }

    @Test
    void aPatchThatChangesValuesIsRejected() {
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .patch(ALICE, from(3, 1), values("name", "Alice Two")).build())));
    }

    @Test
    void aPatchThatChangesNothingIsRejected() {
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .patch(ALICE, from(1, 1), values("name", "Alice")).build())));
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .patch(ALICE, from(3, 1), values()).build())));
    }

    @Test
    void aReplaceThatChangesNothingIsRejected() {
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .replace(ALICE, from(1, 1), values("name", "Alice")).build())));
    }

    @Test
    void anEntityAndARuleAreReportedOnceWhateverTheNumberOfWrites() {
        List<Problem> problems = problemsOf(Transaction.by("importer")
                .patch(ALICE, from(3, 1), values("name", "Alice Two"))
                .patch(ALICE, from(3, 1), values("name", "Alice Two"))
                .replace(ALICE, from(5, 1), values("name", "Alice Three"))
                .endDate(ALICE, on(8, 1)).build());

        assertUnavailable(only(problems));
    }

    @Test
    void anEntityOfATypeThatNamesNoDeclaredRuleIsNotAffected() {
        List<Problem> problems = problemsOf(Transaction.by("importer")
                .create(HOME, "Address", from(1, 1), values("street", "Main Street", "city", "Rome", "country", "IT"))
                .relate("HasAddress", ALICE, HOME, from(1, 1), values()).build());

        assertThat(problems).as("Alice is not written, so her rule does not matter").isEmpty();
    }

    @Test
    void anEntityWithADeclarativeProblemIsNotReportedForItsRules() {
        List<Problem> problems = problemsOf(Transaction.by("importer")
                .patch(ALICE, from(3, 1), values("nmae", "Alice Two")).build());

        assertThat(problems).extracting(Problem::code).containsExactly(ProblemCode.UNKNOWN_ATTRIBUTE);
    }

    @Test
    void theProblemOfARuleWithoutCodeComesAfterTheDeclarativeProblemsOfEveryEntity() {
        List<Problem> problems = problemsOf(Transaction.by("importer")
                .patch(ALICE, from(1, 1), values("name", "Alice"))
                .create(HOME, "Address", from(1, 1), values("street", "Main Street", "country", "IT"))
                .relate("HasAddress", ALICE, HOME, from(1, 1), values()).build());

        assertThat(problems).extracting(Problem::code, Problem::path).containsExactly(
                tuple(ProblemCode.MISSING_REQUIRED_VALUE, "$home.city"),
                tuple(ProblemCode.RULE_UNAVAILABLE, ALICE.value()));
    }
}
