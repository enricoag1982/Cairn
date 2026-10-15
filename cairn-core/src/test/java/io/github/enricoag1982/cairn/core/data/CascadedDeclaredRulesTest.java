package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.kv.InMemoryKeyValueStore;
import io.github.enricoag1982.cairn.core.schema.InMemorySchemaRegistry;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Multiplicity;
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
 * A relationship whose type names a rule that the store declares without its code, which a composition cascade ends
 * (design D2 of {@code change-composition-cascades}, design D5 of {@code add-relationships}): the transaction is
 * rejected with {@code RULE_UNAVAILABLE}, as it is for an explicit end of the relationship (see
 * {@link DeclaredRelationshipRulesTest}), because the cascade's end counts as a write.
 *
 * <p>Beta, an organization, sponsors Alice, a person, through the test-only composition {@code Sponsors}, and employs
 * her through {@code Employs}, which names {@code employment-check} after the relationship was recorded. A schema cannot
 * add a rule to a type that has relationships, so the tests put the entities and the relationships into the store's
 * data by hand, as {@link DeclaredRelationshipRulesTest} does, and prepare transactions the way a store does.
 */
class CascadedDeclaredRulesTest {

    private static final RuleDescriptor EMPLOYMENT_CHECK = new RuleDescriptor("employment-check", 1,
            "Checks employments.", "Employs", List.of(new RuleCode("odd", "Odd.")));
    private static final EntityId BETA = new EntityId("00000000-0000-7000-8000-000000000002");
    private static final EntityId ALICE = new EntityId("00000000-0000-7000-8000-000000000003");
    private static final RelationshipId EMPLOYS = new RelationshipId("00000000-0000-7000-8000-000000000004");
    private static final RelationshipId SPONSORS = new RelationshipId("00000000-0000-7000-8000-000000000005");
    private static final Instant RECORDED = Instant.parse("2026-01-05T09:00:00Z");
    private static final Instant NEXT_COMMIT_AT = Instant.parse("2026-03-16T09:00:00Z");

    private final CommitSequence sequence = new CommitSequence(new TestClock());
    private final Ledger ledger = new Ledger(new InMemoryKeyValueStore());
    private Schema schema;

    @BeforeEach
    void aSchemaThatNamesTheDeclaredRuleAndRelationshipsThatAlreadyExist() {
        List<RuleDescriptor> available = new ArrayList<>(ReferenceModel.rules());
        available.add(EMPLOYMENT_CHECK);
        InMemorySchemaRegistry registry = new InMemorySchemaRegistry(sequence, available);
        SchemaDefinition model = Definitions.plus(Definitions.changeRelationshipType(ReferenceModel.definition(),
                "Employs", employs -> new RelationshipTypeDefinition(employs.name(), employs.source(),
                        employs.target(), employs.targetsPerSource(), employs.sourcesPerTarget(),
                        employs.inverseName(), employs.inverseAliases(), employs.kind(), employs.symmetric(),
                        employs.supertype(), employs.attributes(), employs.aliases(), employs.deprecated(),
                        employs.description(),
                        List.of(new RuleReference(EMPLOYMENT_CHECK.name(), EMPLOYMENT_CHECK.version())))),
                SchemaDefinition.builder().relationshipType("Sponsors", r -> r.from("Organization").to("Person")
                        .composition().targetsPerSource(Multiplicity.ZERO_OR_MORE)
                        .sourcesPerTarget(Multiplicity.EXACTLY_ONE).inverse("sponsor")).build());
        assertThat(registry.register(ReferenceModel.origin("1.0.0"), model))
                .isInstanceOf(RegistrationResult.Applied.class);
        schema = registry.current();

        EntityType organization = schema.entityType("Organization").orElseThrow();
        EntityType person = schema.entityType("Person").orElseThrow();
        store(BETA, organization, "Beta");
        store(ALICE, person, "Alice");
        relate(EMPLOYS, schema.relationshipType("Employs").orElseThrow(), on(3, 1));
        relate(SPONSORS, schema.relationshipType("Sponsors").orElseThrow(), on(1, 1));
    }

    private void store(EntityId id, EntityType type, String name) {
        ledger.write(new Basis(2), List.of(new Preparation.Change(id, type.id(), null, null, true,
                Timeline.created(on(1, 1), Segment.OPEN,
                        Map.of(type.attribute("name").orElseThrow().id(), Value.of(name)), RECORDED,
                        new Basis(2)))), new SchemaIndex(schema));
    }

    private void relate(RelationshipId id, RelationshipType type, Instant from) {
        ledger.write(new Basis(2), List.of(new Preparation.Change(id, type.id(), BETA, ALICE, true,
                Timeline.created(from, Segment.OPEN, Map.of(), RECORDED, new Basis(2)))), new SchemaIndex(schema));
    }

    /** Prepares a transaction as the store would, as the third commit, with only the code of the reference rules. */
    private Preparation prepare(Transaction transaction) {
        return sequence.underWriterLock(() -> new Preparation(transaction, new SchemaIndex(schema), ledger,
                Set.of(), IdGenerators.deterministic(),
                new CommitSequence.Commit(new Basis(3), NEXT_COMMIT_AT), new ValueChecks(),
                new Rules(ReferenceModel.executableRules()), sequence).run());
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    @Test
    void aCascadeThatEndsARelationshipWhoseRuleHasNoCodeIsRejectedAsAnExplicitEndIs() {
        Problem explicit = only(prepare(Transaction.by("importer").endDate(EMPLOYS, on(6, 1)).build()).problems());

        Preparation cascade = prepare(Transaction.by("importer").endDate(BETA, on(6, 1)).build());

        Problem problem = only(cascade.problems());
        assertThat(problem.code()).isEqualTo(ProblemCode.RULE_UNAVAILABLE);
        assertThat(problem.path()).as("the employment of Alice, which the transaction does not name")
                .isEqualTo(EMPLOYS.value());
        assertThat(problem.message()).contains("employment-check");
        assertThat(problem).isEqualTo(explicit);
        assertThat(cascade.ended()).as("the cascade ended it, and it is the cause").containsExactly(SPONSORS, ALICE,
                EMPLOYS);
    }
}
