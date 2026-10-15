package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.kv.InMemoryKeyValueStore;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RelationshipType;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rules that a store declares without having their code (design D5 of {@code add-relationships}), on relationship types:
 * any write of a relationship whose type, or one of whose supertypes, names such a rule is rejected, whatever the write
 * changes, as it is for entities (see {@link DeclaredRulesTest}).
 *
 * <p>Beta employs Alice before Employs names {@code employment-check}, which the store declares without its code. A
 * schema cannot add a rule to a type that has relationships (that is a breaking change), so no store gets there by
 * itself in memory; it is what a store would find if it were created again without the code of a rule that it had when
 * the relationship was recorded. The tests therefore put the entities and the relationship into the store's data by
 * hand, and prepare transactions the way a store does.
 */
class DeclaredRelationshipRulesTest {

    private static final RuleDescriptor EMPLOYMENT_CHECK = new RuleDescriptor("employment-check", 1,
            "Checks employments.", "Employs", List.of(new RuleCode("odd", "Odd.")));
    private static final EntityId BETA = new EntityId("00000000-0000-7000-8000-000000000002");
    private static final EntityId ALICE = new EntityId("00000000-0000-7000-8000-000000000003");
    private static final RelationshipId EMPLOYS = new RelationshipId("00000000-0000-7000-8000-000000000004");
    private static final TemporaryId NEW_EMPLOYS = new TemporaryId("newEmploys");
    private static final Instant RECORDED = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant NEXT_COMMIT_AT = Instant.parse("2026-03-16T09:00:00Z");

    private final CommitSequence sequence = new CommitSequence(new TestClock());
    private final Ledger ledger = new Ledger(new InMemoryKeyValueStore());
    private Schema schema;

    @BeforeEach
    void aSchemaThatNamesTheDeclaredRuleAndARelationshipThatAlreadyExists() {
        List<RuleDescriptor> available = new ArrayList<>(ReferenceModel.rules());
        available.add(EMPLOYMENT_CHECK);
        InMemorySchemaRegistry registry = new InMemorySchemaRegistry(sequence, available);
        SchemaDefinition model = Definitions.changeRelationshipType(ReferenceModel.definition(), "Employs", employs ->
                new RelationshipTypeDefinition(employs.name(), employs.source(), employs.target(),
                        employs.targetsPerSource(), employs.sourcesPerTarget(), employs.inverseName(),
                        employs.inverseAliases(), employs.kind(), employs.symmetric(), employs.supertype(),
                        employs.attributes(), employs.aliases(), employs.deprecated(), employs.description(),
                        List.of(new RuleReference(EMPLOYMENT_CHECK.name(), EMPLOYMENT_CHECK.version()))));
        assertThat(registry.register(ReferenceModel.origin("1.0.0"), model))
                .isInstanceOf(RegistrationResult.Applied.class);
        schema = registry.current();

        EntityType organization = schema.entityType("Organization").orElseThrow();
        EntityType person = schema.entityType("Person").orElseThrow();
        RelationshipType employs = schema.relationshipType("Employs").orElseThrow();
        store(BETA, organization, "Beta");
        store(ALICE, person, "Alice");
        ledger.write(new Basis(2), List.of(new Preparation.Change(EMPLOYS, employs.id(), BETA, ALICE, true,
                Timeline.created(on(3, 1), Segment.OPEN, Map.of(), RECORDED, new Basis(2)))), new SchemaIndex(schema));
    }

    private void store(EntityId id, EntityType type, String name) {
        ledger.write(new Basis(2), List.of(new Preparation.Change(id, type.id(), null, null, true,
                Timeline.created(on(1, 1), Segment.OPEN,
                        Map.of(type.attribute("name").orElseThrow().id(), Value.of(name)), RECORDED,
                        new Basis(2)))), new SchemaIndex(schema));
    }

    /** Prepares a transaction as the store would, as the third commit, with only the code of the reference rules. */
    private List<Problem> problemsOf(Transaction transaction) {
        return sequence.underWriterLock(() -> new Preparation(transaction, new SchemaIndex(schema), ledger,
                Set.of(), IdGenerators.deterministic(),
                new CommitSequence.Commit(new Basis(3), NEXT_COMMIT_AT), new ValueChecks(), new Rules(ReferenceModel.executableRules()), sequence).run().problems());
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    private static void assertUnavailable(Problem problem, String path) {
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
        assertThat(problem.path()).isEqualTo(path);
        assertThat(problem.message()).contains("employment-check").contains(path);
    }

    @Test
    void endingARelationshipWhoseRuleHasNoCodeIsRejected() {
        assertUnavailable(only(problemsOf(Transaction.by("importer").endDate(EMPLOYS, on(6, 1)).build())),
                EMPLOYS.value());
    }

    @Test
    void aPatchThatChangesValuesOrNothingIsRejected() {
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .patch(EMPLOYS, from(6, 1), values("title", "Analyst")).build())), EMPLOYS.value());
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .patch(EMPLOYS, from(3, 1), values()).build())), EMPLOYS.value());
        assertUnavailable(only(problemsOf(Transaction.by("importer")
                .replace(EMPLOYS, from(3, 1), values()).build())), EMPLOYS.value());
    }

    @Test
    void relatingEntitiesWithATypeWhoseRuleHasNoCodeIsRejected() {
        TemporaryId delta = new TemporaryId("delta");

        List<Problem> problems = problemsOf(Transaction.by("importer")
                .create(delta, "Organization", from(1, 1), values("name", "Delta"))
                .relate(NEW_EMPLOYS, "Employs", delta, ALICE, from(8, 1), values()).build());

        assertUnavailable(only(problems), "$newEmploys");
    }

    @Test
    void aRelationshipIsReportedOnceWhateverTheNumberOfWrites() {
        List<Problem> problems = problemsOf(Transaction.by("importer")
                .patch(EMPLOYS, from(6, 1), values("title", "Analyst"))
                .patch(EMPLOYS, from(7, 1), values("title", "Senior analyst"))
                .endDate(EMPLOYS, on(8, 1)).build());

        assertUnavailable(only(problems), EMPLOYS.value());
    }

    @Test
    void writesThatNeedNoDeclaredRuleAreNotAffected() {
        List<Problem> problems = problemsOf(Transaction.by("importer")
                .patch(BETA, from(1, 1), values("name", "Beta Two"))
                .relate("Owns", BETA, ALICE, from(2, 1), values("share", 10)).build());

        assertThat(problems).as("an end of the relationship, and a relationship of a type with other rules").isEmpty();
    }
}
